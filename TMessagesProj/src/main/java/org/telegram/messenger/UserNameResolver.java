package org.telegram.messenger;

import android.text.TextUtils;
import android.util.LruCache;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;
import java.util.HashMap;

public class UserNameResolver {

    private final int currentAccount;
    private final static long CACHE_TIME = 1000 * 60 * 60; //1 hour

    // SilentGram: a resolveUsername RPC has no client-side timeout upstream, and the
    // native layer retries generic requests forever (no retry cap — only downloads
    // have one). A lost response leaves the button spinner spinning forever, and the
    // dedup entry then blocks every later tap for the same username. Bound every
    // resolve: 5s per attempt, one retry on a fresh request, then fail all consumers
    // with null so the UI always gets a definitive answer (spinner dismissed, error
    // bulletin shown) instead of hanging forever.
    private final static long RESOLVE_TIMEOUT_MS = 5000;
    private final static int RESOLVE_MAX_ATTEMPTS = 2;

    UserNameResolver(int currentAccount) {
        this.currentAccount = currentAccount;
    }

    LruCache<String, CachedPeer> resolvedCache = new LruCache<>(100);
    HashMap<String, ArrayList<Utilities.Callback<Long>>> resolvingConsumers = new HashMap<>();
    HashMap<String, ResolveState> resolvingStates = new HashMap<>();

    private static class ResolveState {
        String referrer;
        int reqId;
        int attempts;
        int attemptId;
        Runnable timeoutRunnable;
    }

    public Runnable resolve(String username, Utilities.Callback<Long> resolveConsumer) {
        return resolve(username, null, resolveConsumer);
    }

    public Runnable resolve(String username, String referrer, Utilities.Callback<Long> resolveConsumer) {
        return resolve(username, referrer, false, resolveConsumer);
    }

    public Runnable resolve(String username, String referrer, boolean force, Utilities.Callback<Long> resolveConsumer) {
        if (TextUtils.isEmpty(referrer) && !force) {
            CachedPeer cachedPeer = resolvedCache.get(username);
            if (cachedPeer != null) {
                if (System.currentTimeMillis() - cachedPeer.time < CACHE_TIME) {
                    resolveConsumer.run(cachedPeer.peerId);
                    FileLog.d("resolve username from cache " + username + " " + cachedPeer.peerId);
                    return null;
                } else {
                    resolvedCache.remove(username);
                }
            }
        }

        ArrayList<Utilities.Callback<Long>> consumers = resolvingConsumers.get(username);
        if (consumers != null) {
            consumers.add(resolveConsumer);
            return cancelRunnable(username, resolveConsumer);
        }
        consumers = new ArrayList<>();
        consumers.add(resolveConsumer);
        resolvingConsumers.put(username, consumers);

        ResolveState state = new ResolveState();
        state.referrer = referrer;
        resolvingStates.put(username, state);
        sendResolveRequest(username, state);
        return cancelRunnable(username, resolveConsumer);
    }

    private TLObject buildResolveRequest(String username, String referrer) {
        if (AndroidUtilities.isNumeric(username)) {
            TLRPC.TL_contacts_resolvePhone resolvePhone = new TLRPC.TL_contacts_resolvePhone();
            resolvePhone.phone = username;
            return resolvePhone;
        } else {
            TLRPC.TL_contacts_resolveUsername resolveUsername = new TLRPC.TL_contacts_resolveUsername();
            resolveUsername.username = username;
            if (!TextUtils.isEmpty(referrer)) {
                resolveUsername.flags |= 1;
                resolveUsername.referer = referrer;
            }
            return resolveUsername;
        }
    }

    private void sendResolveRequest(final String username, final ResolveState state) {
        state.attempts++;
        state.attemptId++;
        final int attemptId = state.attemptId;
        TLObject req = buildResolveRequest(username, state.referrer);
        state.reqId = ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            ResolveState current = resolvingStates.get(username);
            if (current != state || state.attemptId != attemptId) {
                return;
            }
            cancelResolveTimeout(state);
            ArrayList<Utilities.Callback<Long>> finalConsumers = resolvingConsumers.remove(username);
            resolvingStates.remove(username);
            if (finalConsumers == null) {
                return;
            }
            if (error != null) {
                if (error.text != null && "STARREF_EXPIRED".equals(error.text)) {
                    for (int i = 0; i < finalConsumers.size(); i++) {
                        finalConsumers.get(i).run(Long.MAX_VALUE);
                    }
                    return;
                }
                for (int i = 0; i < finalConsumers.size(); i++) {
                    finalConsumers.get(i).run(null);
                }

                if (error.text != null && error.text.contains("FLOOD_WAIT")) {
                    BaseFragment fragment = LaunchActivity.getLastFragment();
                    if (fragment != null) {
                        BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.FloodWait)).show();
                    }
                }
                return;
            }
            TLRPC.TL_contacts_resolvedPeer res = (TLRPC.TL_contacts_resolvedPeer) response;

            MessagesController.getInstance(currentAccount).putUsers(res.users, false);
            MessagesController.getInstance(currentAccount).putChats(res.chats, false);
            MessagesStorage.getInstance(currentAccount).putUsersAndChats(res.users, res.chats, false, true);

            long peerId = MessageObject.getPeerId(res.peer);
            resolvedCache.put(username, new CachedPeer(peerId));
            for (int i = 0; i < finalConsumers.size(); i++) {
                finalConsumers.get(i).run(peerId);
            }
        }, ConnectionsManager.RequestFlagFailOnServerErrors));
        scheduleResolveTimeout(username, state);
    }

    private void scheduleResolveTimeout(final String username, final ResolveState state) {
        cancelResolveTimeout(state);
        state.timeoutRunnable = () -> onResolveTimeout(username, state);
        AndroidUtilities.runOnUIThread(state.timeoutRunnable, RESOLVE_TIMEOUT_MS);
    }

    private void cancelResolveTimeout(ResolveState state) {
        if (state.timeoutRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(state.timeoutRunnable);
            state.timeoutRunnable = null;
        }
    }

    private void onResolveTimeout(final String username, final ResolveState state) {
        if (resolvingStates.get(username) != state) {
            return;
        }
        ArrayList<Utilities.Callback<Long>> consumers = resolvingConsumers.get(username);
        if (consumers == null) {
            resolvingStates.remove(username);
            return;
        }
        if (state.attempts < RESOLVE_MAX_ATTEMPTS) {
            FileLog.d("resolve username timeout, retrying " + username + " attempt " + (state.attempts + 1));
            ConnectionsManager.getInstance(currentAccount).cancelRequest(state.reqId, true);
            sendResolveRequest(username, state);
        } else {
            FileLog.d("resolve username timeout, giving up " + username);
            resolvingConsumers.remove(username);
            resolvingStates.remove(username);
            ArrayList<Utilities.Callback<Long>> finalConsumers = new ArrayList<>(consumers);
            for (int i = 0; i < finalConsumers.size(); i++) {
                finalConsumers.get(i).run(null);
            }
        }
    }

    private Runnable cancelRunnable(final String username, final Utilities.Callback<Long> resolveConsumer) {
        return () -> {
            ArrayList<Utilities.Callback<Long>> consumers = resolvingConsumers.get(username);
            if (consumers == null) {
                return;
            }
            consumers.remove(resolveConsumer);
            if (!consumers.isEmpty()) {
                return;
            }
            ResolveState state = resolvingStates.remove(username);
            resolvingConsumers.remove(username);
            if (state != null) {
                cancelResolveTimeout(state);
                ConnectionsManager.getInstance(currentAccount).cancelRequest(state.reqId, true);
            }
        };
    }

    public void update(TLRPC.User oldUser, TLRPC.User user) {
        if (oldUser == null || user == null || oldUser.username == null || TextUtils.equals(oldUser.username, user.username)) {
            return;
        }
        resolvedCache.remove(oldUser.username);
        if (user.username != null) {
            resolvedCache.put(user.username, new CachedPeer(user.id));
        }
    }

    public void update(TLRPC.Chat oldChat, TLRPC.Chat chat) {
        if (oldChat == null || chat == null || oldChat.username == null || TextUtils.equals(oldChat.username, chat.username)) {
            return;
        }
        resolvedCache.remove(oldChat.username);
        if (chat.username != null) {
            resolvedCache.put(chat.username, new CachedPeer(-chat.id));
        }
    }

    private class CachedPeer {
        final long peerId;
        final long time;

        public CachedPeer(long peerId) {
            this.peerId = peerId;
            time = System.currentTimeMillis();
        }
    }
}
