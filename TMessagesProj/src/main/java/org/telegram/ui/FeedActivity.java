package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.style.CharacterStyle;
import android.text.style.URLSpan;
import android.util.LongSparseArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.EmptyTextProgressView;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerAnimationScrollHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SizeNotifierFrameLayout;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.Components.UItem;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

public class FeedActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate, MainTabsActivity.TabFragmentDelegate {

    public boolean hasMainTabs;

    private static final int MAX_CHANNELS = 100;
    private static final int MESSAGES_PER_CHANNEL = 25;
    private static final int MAX_FEED_POSTS = 300;
    private static final long REBUILD_DEBOUNCE_MS = 150;
    private static final long REFRESH_INTERVAL_MS = 3 * 60 * 1000L;

    private UniversalRecyclerView listView;
    private RecyclerAnimationScrollHelper scrollHelper;
    private EmptyTextProgressView emptyView;
    private FlickerLoadingView flickerLoadingView;
    private SizeNotifierFrameLayout contentView;

    private final HashMap<Long, TLRPC.Chat> feedChannels = new HashMap<>();
    private final LongSparseArray<ArrayList<FeedPost>> channelMessages = new LongSparseArray<>();
    private final HashSet<Long> pendingCacheLoads = new HashSet<>();
    private final HashSet<Long> pendingServerLoads = new HashSet<>();
    private final HashSet<Long> serverRefreshed = new HashSet<>();
    private final ArrayList<FeedPost> mergedPosts = new ArrayList<>();

    private final ChatMessageCell.ChatMessageCellDelegate cellDelegate = new ChatMessageCell.ChatMessageCellDelegate() {
        @Override
        public boolean canPerformActions() {
            return true;
        }

        @Override
        public void didPressCommentButton(ChatMessageCell cell) {
            openComments(feedMessageOf(cell).post);
        }

        @Override
        public void didPressOther(ChatMessageCell cell, float otherX, float otherY) {
            openChat(cell.getMessageObject());
        }

        @Override
        public void didPressChannelAvatar(ChatMessageCell cell, TLRPC.Chat chat, int postId, float touchX, float touchY, boolean asForward) {
            openChannel(chat != null ? chat : feedMessageOf(cell).post.chat, postId);
        }

        @Override
        public void didPressUserAvatar(ChatMessageCell cell, TLRPC.User user, float touchX, float touchY, boolean asForward) {
            openChannel(feedMessageOf(cell).post.chat, 0);
        }

        @Override
        public void didPressReplyMessage(ChatMessageCell cell, int id, float x, float y, boolean longpress) {
            openChannel(feedMessageOf(cell).post.chat, id);
        }

        @Override
        public void didPressImage(ChatMessageCell cell, float x, float y, boolean fullPreview) {
            final FeedPost post = feedMessageOf(cell).post;
            final int index = post.media.indexOf(cell.getMessageObject());
            if (index >= 0) {
                openPhotoViewer(post, index);
            } else {
                openChat(cell.getMessageObject());
            }
        }

        @Override
        public void didPressUrl(ChatMessageCell cell, CharacterStyle url, boolean longPress) {
            if (url instanceof URLSpan) {
                Browser.openUrl(cell.getContext(), ((URLSpan) url).getURL());
            } else {
                openChat(cell.getMessageObject());
            }
        }

        @Override
        public void didPressVoteButtons(ChatMessageCell cell, ArrayList<TLRPC.PollAnswer> buttons, int showCount, int x, int y) {
            openChat(cell.getMessageObject());
        }
    };

    private boolean loading = true;
    private long lastFeedLoadTime;
    private boolean dialogsReloadScheduled;
    private boolean rebuildScheduled;
    private int navigationBarHeight;

    public FeedActivity(Bundle args) {
        super(args);
    }

    @Override
    public boolean onFragmentCreate() {
        if (arguments != null) {
            hasMainTabs = arguments.getBoolean("hasMainTabs", false);
        }
        getNotificationCenter().addObserver(this, NotificationCenter.messagesDidLoad);
        getNotificationCenter().addObserver(this, NotificationCenter.dialogsNeedReload);
        getNotificationCenter().addObserver(this, NotificationCenter.wallpapersDidLoad);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.messagesDidLoad);
        getNotificationCenter().removeObserver(this, NotificationCenter.dialogsNeedReload);
        getNotificationCenter().removeObserver(this, NotificationCenter.wallpapersDidLoad);
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.MainTabsFeed));
        if (!hasMainTabs) {
            actionBar.setBackButtonDrawable(new BackDrawable(false));
        }

        flickerLoadingView = new FlickerLoadingView(context);
        flickerLoadingView.setViewType(FlickerLoadingView.DIALOG_CELL_TYPE);
        flickerLoadingView.showDate(false);

        emptyView = new EmptyTextProgressView(context, flickerLoadingView);
        emptyView.setText(getString(R.string.FeedEmpty));

        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, null);
        listView.adapter.setApplyBackground(false);
        listView.setClipToPadding(false);
        listView.setEmptyView(emptyView);
        scrollHelper = new RecyclerAnimationScrollHelper(listView, listView.layoutManager);

        contentView = new SizeNotifierFrameLayout(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                measureChildWithMargins(actionBar, widthMeasureSpec, 0, heightMeasureSpec, 0);
                if (emptyView.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                    ((ViewGroup.MarginLayoutParams) emptyView.getLayoutParams()).topMargin = actionBar.getMeasuredHeight();
                }
                checkUi_listViewPadding();
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }
        };
        contentView.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        fragmentView = contentView;
        updateBackground();

        listView.adapter.update(false);
        updateEmptyState();

        return fragmentView;
    }

    private void updateBackground() {
        if (contentView == null) {
            return;
        }
        contentView.setBackgroundImage(Theme.getCachedWallpaper(), Theme.isWallpaperMotion());
    }

    private void checkUi_listViewPadding() {
        if (listView == null || actionBar == null) {
            return;
        }
        final int tabsOffset = hasMainTabs ? dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS) : 0;
        listView.setPadding(0, actionBar.getMeasuredHeight(), 0, navigationBarHeight + tabsOffset);
        emptyView.setPadding(0, 0, 0, navigationBarHeight + tabsOffset);
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        navigationBarHeight = bottom;
        checkUi_listViewPadding();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (contentView != null) {
            contentView.onResume();
        }
        updateBackground();
        ensureLoaded();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (contentView != null) {
            contentView.onPause();
        }
    }

    /* Feed data */

    private void ensureLoaded() {
        collectChannels();

        final boolean stale = System.currentTimeMillis() - lastFeedLoadTime > REFRESH_INTERVAL_MS;
        for (Map.Entry<Long, TLRPC.Chat> entry : feedChannels.entrySet()) {
            final long dialogId = entry.getKey();
            if (channelMessages.indexOfKey(dialogId) < 0 && !pendingCacheLoads.contains(dialogId)) {
                loadChannelMessages(dialogId, true);
            }
            if (!pendingServerLoads.contains(dialogId) && (stale || !serverRefreshed.contains(dialogId))) {
                loadChannelMessages(dialogId, false);
            }
        }
        lastFeedLoadTime = System.currentTimeMillis();
        loading = !pendingCacheLoads.isEmpty();
        updateEmptyState();
    }

    private void collectChannels() {
        final HashMap<Long, TLRPC.Chat> channels = new HashMap<>();
        final ArrayList<TLRPC.Dialog> dialogs = getMessagesController().getAllDialogs();
        for (int i = 0; i < dialogs.size() && channels.size() < MAX_CHANNELS; i++) {
            final TLRPC.Dialog dialog = dialogs.get(i);
            if (dialog.id >= 0 || DialogObject.isEncryptedDialog(dialog.id)) {
                continue;
            }
            final TLRPC.Chat chat = getMessagesController().getChat(-dialog.id);
            if (chat == null) {
                continue;
            }
            if (!ChatObject.isChannel(chat) || chat.megagroup) {
                continue;
            }
            if (chat.left || chat.kicked) {
                continue;
            }
            channels.put(dialog.id, chat);
        }
        feedChannels.keySet().retainAll(channels.keySet());
        feedChannels.putAll(channels);
        for (int i = channelMessages.size() - 1; i >= 0; i--) {
            if (!channels.containsKey(channelMessages.keyAt(i))) {
                channelMessages.removeAt(i);
            }
        }
    }

    private void loadChannelMessages(long dialogId, boolean fromCache) {
        if (fromCache) {
            pendingCacheLoads.add(dialogId);
            getMessagesController().loadMessages(dialogId, 0, false, MESSAGES_PER_CHANNEL, 0, 0, true, 0, classGuid, MessagesController.LOAD_BACKWARD, 0, 0, 0, 0, 0, false);
        } else {
            pendingServerLoads.add(dialogId);
            getMessagesController().loadMessages(dialogId, 0, false, MESSAGES_PER_CHANNEL, 0, 0, false, 0, classGuid, MessagesController.LOAD_BACKWARD, 0, 0, 0, 0, 0, 0, 0, true, 0, false);
        }
    }

    private void scheduleRebuildMerged() {
        if (rebuildScheduled) {
            return;
        }
        rebuildScheduled = true;
        AndroidUtilities.runOnUIThread(() -> {
            rebuildScheduled = false;
            rebuildMerged();
        }, REBUILD_DEBOUNCE_MS);
    }

    private void rebuildMerged() {
        // group album members (same dialog + grouped_id) into a single post
        final HashMap<String, FeedPost> albums = new HashMap<>();
        final ArrayList<FeedPost> merged = new ArrayList<>();
        for (int i = 0; i < channelMessages.size(); i++) {
            final ArrayList<FeedPost> posts = channelMessages.valueAt(i);
            if (posts == null) {
                continue;
            }
            for (int j = 0; j < posts.size(); j++) {
                final FeedPost post = posts.get(j);
                final long groupedId = post.getMainMessage().messageOwner.grouped_id;
                if (groupedId == 0) {
                    merged.add(post);
                } else {
                    final String key = post.dialogId + ":" + groupedId;
                    FeedPost album = albums.get(key);
                    if (album == null) {
                        album = new FeedPost(post.dialogId, post.chat, null);
                        albums.put(key, album);
                    }
                    album.messages.add(post.getMainMessage());
                }
            }
        }
        for (FeedPost album : albums.values()) {
            Collections.sort(album.messages, (a, b) -> Integer.compare(a.getId(), b.getId()));
            album.rebuildDerived();
            merged.add(album);
        }
        Collections.sort(merged, (a, b) -> {
            final int dateA = a.getMainMessage().messageOwner.date;
            final int dateB = b.getMainMessage().messageOwner.date;
            if (dateA != dateB) {
                return Integer.compare(dateB, dateA);
            }
            return Integer.compare(b.sortId, a.sortId);
        });
        mergedPosts.clear();
        for (int i = 0; i < merged.size() && mergedPosts.size() < MAX_FEED_POSTS; i++) {
            final FeedPost post = merged.get(i);
            post.chat = feedChannels.get(post.dialogId);
            mergedPosts.add(post);
        }
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void updateEmptyState() {
        if (emptyView == null) {
            return;
        }
        if (loading) {
            emptyView.showProgress();
        } else {
            emptyView.setText(feedChannels.isEmpty() ? getString(R.string.FeedNoChannels) : getString(R.string.FeedEmpty));
            emptyView.showTextView();
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.messagesDidLoad) {
            final int guid = (Integer) args[10];
            if (guid != classGuid) {
                return;
            }
            final long dialogId = (Long) args[0];
            if (!feedChannels.containsKey(dialogId)) {
                // channel was removed (e.g. unsubscribed) while its load was in flight
                pendingCacheLoads.remove(dialogId);
                pendingServerLoads.remove(dialogId);
                loading = !pendingCacheLoads.isEmpty();
                updateEmptyState();
                return;
            }
            final boolean isCache = (Boolean) args[3];
            final ArrayList<MessageObject> objects = (ArrayList<MessageObject>) args[2];

            final ArrayList<FeedPost> posts = new ArrayList<>();
            if (objects != null) {
                final TLRPC.Chat chat = feedChannels.get(dialogId);
                for (int i = 0; i < objects.size(); i++) {
                    final MessageObject messageObject = objects.get(i);
                    if (messageObject == null || messageObject.messageOwner == null || messageObject.messageOwner.action != null) {
                        continue;
                    }
                    final ArrayList<MessageObject> single = new ArrayList<>();
                    single.add(messageObject);
                    posts.add(new FeedPost(dialogId, chat, single));
                }
            }
            channelMessages.put(dialogId, posts);

            if (isCache) {
                pendingCacheLoads.remove(dialogId);
            } else {
                pendingServerLoads.remove(dialogId);
                serverRefreshed.add(dialogId);
            }
            loading = !pendingCacheLoads.isEmpty();
            scheduleRebuildMerged();
            updateEmptyState();
        } else if (id == NotificationCenter.dialogsNeedReload) {
            if (dialogsReloadScheduled) {
                return;
            }
            dialogsReloadScheduled = true;
            AndroidUtilities.runOnUIThread(() -> {
                dialogsReloadScheduled = false;
                if (fragmentView != null) {
                    ensureLoaded();
                }
            }, 1000);
        } else if (id == NotificationCenter.wallpapersDidLoad) {
            updateBackground();
        }
    }

    /* List */

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        int lastDayKey = 0;
        for (int i = 0; i < mergedPosts.size(); i++) {
            final FeedPost post = mergedPosts.get(i);
            final int date = post.getMainMessage().messageOwner.date;
            final int dayKey = dayKeyOf(date);
            if (dayKey != lastDayKey) {
                lastDayKey = dayKey;
                items.add(FeedDateCell.Factory.of(date));
            }
            for (int j = 0; j < post.messages.size(); j++) {
                items.add(FeedMessageCell.Factory.of(new FeedMessage(post, post.messages.get(j), j > 0, j < post.messages.size() - 1, cellDelegate)));
            }
        }
    }

    private void onClick(UItem item, View view, Integer position, Float x, Float y) {
        if (item.object instanceof FeedMessage) {
            openChat(((FeedMessage) item.object).message);
        }
    }

    private void openComments(FeedPost post) {
        final TLRPC.Message message = post.getMainMessage().messageOwner;
        if (message.replies == null || message.replies.channel_id == 0 || message.replies.max_id == 0) {
            openChat(post.getMainMessage());
            return;
        }
        final TLRPC.Chat chat = getMessagesController().getChat(-post.dialogId);
        if (chat == null) {
            openChat(post.getMainMessage());
            return;
        }
        final TLRPC.TL_messages_getDiscussionMessage req = new TLRPC.TL_messages_getDiscussionMessage();
        req.peer = MessagesController.getInputPeer(chat);
        req.msg_id = post.getMainMessage().getId();
        final int reqId = getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (getParentActivity() == null) {
                return;
            }
            if (!(response instanceof TLRPC.TL_messages_discussionMessage)) {
                openChat(post.getMainMessage());
                return;
            }
            final TLRPC.TL_messages_discussionMessage discussionMessage = (TLRPC.TL_messages_discussionMessage) response;
            getMessagesController().putUsers(discussionMessage.users, false);
            getMessagesController().putChats(discussionMessage.chats, false);
            final ArrayList<MessageObject> thread = new ArrayList<>();
            for (int i = 0; i < discussionMessage.messages.size(); i++) {
                final TLRPC.Message m = discussionMessage.messages.get(i);
                if (m instanceof TLRPC.TL_messageEmpty) {
                    continue;
                }
                m.isThreadMessage = true;
                thread.add(new MessageObject(currentAccount, m, true, true));
            }
            if (thread.isEmpty()) {
                openChat(post.getMainMessage());
                return;
            }
            final Bundle args = new Bundle();
            args.putLong("chat_id", -thread.get(0).getDialogId());
            args.putInt("message_id", Math.max(1, discussionMessage.read_inbox_max_id));
            final ChatActivity chatActivity = new ChatActivity(args);
            chatActivity.setThreadMessages(thread, chat, req.msg_id, discussionMessage.read_inbox_max_id, discussionMessage.read_outbox_max_id, null);
            presentFragment(chatActivity);
        }));
        getConnectionsManager().bindRequestToGuid(reqId, classGuid);
    }

    private void openPhotoViewer(FeedPost post, int index) {
        if (getParentActivity() == null) {
            return;
        }
        final PhotoViewer photoViewer = PhotoViewer.getInstance();
        photoViewer.setParentActivity(getParentActivity());
        photoViewer.openPhoto(post.media, index, post.dialogId, 0, 0, new PhotoViewer.EmptyPhotoViewerProvider());
    }

    private void openChat(MessageObject message) {
        Bundle args = new Bundle();
        args.putLong("dialog_id", message.getDialogId());
        args.putInt("message_id", message.getId());
        getNotificationCenter().postNotificationName(NotificationCenter.closeChats);
        presentFragment(new ChatActivity(args));
    }

    private void openChannel(TLRPC.Chat chat, int messageId) {
        if (chat == null) {
            return;
        }
        Bundle args = new Bundle();
        args.putLong("dialog_id", -chat.id);
        if (messageId != 0) {
            args.putInt("message_id", messageId);
        }
        getNotificationCenter().postNotificationName(NotificationCenter.closeChats);
        presentFragment(new ChatActivity(args));
    }

    private static int dayKeyOf(int date) {
        final Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis((long) date * 1000);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return (int) (calendar.getTimeInMillis() / 86400000L);
    }

    @Override
    public boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
        return true;
    }

    @Override
    public void onParentScrollToTop() {
        if (scrollHelper != null) {
            scrollHelper.setScrollDirection(RecyclerAnimationScrollHelper.SCROLL_DIRECTION_UP);
            scrollHelper.scrollToPosition(0, 0, false, true);
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        final ThemeDescription.ThemeDescriptionDelegate cellDelegate = () -> {
            updateBackground();
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(false);
            }
        };

        ArrayList<ThemeDescription> themeDescriptions = new ArrayList<>();
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_LISTGLOWCOLOR, null, null, null, null, Theme.key_actionBarDefault));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_SELECTOR, null, null, null, null, Theme.key_listSelector));
        themeDescriptions.add(new ThemeDescription(emptyView, ThemeDescription.FLAG_TEXTCOLOR, new Class[]{EmptyTextProgressView.class}, new String[]{"emptyTextView1"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(emptyView, ThemeDescription.FLAG_TEXTCOLOR, new Class[]{EmptyTextProgressView.class}, new String[]{"emptyTextView2"}, null, null, null, Theme.key_emptyListPlaceholder));

        themeDescriptions.add(new ThemeDescription(null, 0, null, null, null, cellDelegate, Theme.key_chat_inBubble));
        themeDescriptions.add(new ThemeDescription(null, 0, null, null, null, cellDelegate, Theme.key_chat_messageTextIn));
        themeDescriptions.add(new ThemeDescription(null, 0, null, null, null, cellDelegate, Theme.key_chat_inTimeText));
        themeDescriptions.add(new ThemeDescription(null, 0, null, null, null, cellDelegate, Theme.key_chat_inViews));
        themeDescriptions.add(new ThemeDescription(null, 0, null, null, null, cellDelegate, Theme.key_chat_linkSelectBackground));
        themeDescriptions.add(new ThemeDescription(null, 0, null, null, null, cellDelegate, Theme.key_chat_serviceBackground));
        themeDescriptions.add(new ThemeDescription(null, 0, null, null, null, cellDelegate, Theme.key_chat_serviceText));
        for (int a = 0; a < Theme.keys_avatar_background.length; a++) {
            themeDescriptions.add(new ThemeDescription(null, 0, null, null, null, cellDelegate, Theme.keys_avatar_background[a]));
        }

        return themeDescriptions;
    }

    /* */

    private static class FeedPost {
        final long dialogId;
        TLRPC.Chat chat;
        final ArrayList<MessageObject> messages = new ArrayList<>();
        final ArrayList<MessageObject> media = new ArrayList<>();
        MessageObject.GroupedMessages group;
        boolean hasDiscussion;
        int sortId;

        FeedPost(long dialogId, TLRPC.Chat chat, ArrayList<MessageObject> messages) {
            this.dialogId = dialogId;
            this.chat = chat;
            if (messages != null) {
                this.messages.addAll(messages);
            }
            rebuildDerived();
        }

        // called after album messages were collected
        void rebuildDerived() {
            media.clear();
            hasDiscussion = false;
            int maxId = 0;
            for (int i = 0; i < messages.size(); i++) {
                final MessageObject messageObject = messages.get(i);
                maxId = Math.max(maxId, messageObject.getId());
                if (isMediaMessage(messageObject)) {
                    media.add(messageObject);
                }
                final TLRPC.Message raw = messageObject.messageOwner;
                if (raw.replies != null && raw.replies.channel_id != 0) {
                    hasDiscussion = true;
                }
            }
            sortId = maxId;
            group = null;
            if (messages.size() > 1) {
                group = new MessageObject.GroupedMessages();
                group.groupId = getMainMessage().getGroupId();
                group.messages.addAll(messages);
                group.calculate();
            }
        }

        MessageObject getMainMessage() {
            return messages.get(0);
        }

        static boolean isMediaMessage(MessageObject messageObject) {
            return (messageObject.type == MessageObject.TYPE_PHOTO || messageObject.type == MessageObject.TYPE_VIDEO || messageObject.type == MessageObject.TYPE_GIF)
                && messageObject.photoThumbs != null && !messageObject.photoThumbs.isEmpty();
        }
    }

    private static class FeedDateCell extends FrameLayout {

        private final TextView textView;
        private final Theme.ResourcesProvider resourcesProvider;

        public FeedDateCell(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.resourcesProvider = resourcesProvider;
            setLayoutParams(new RecyclerView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            setPadding(dp(14), dp(10), dp(14), dp(2));
            textView = new TextView(context);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            textView.setTypeface(AndroidUtilities.bold());
            textView.setGravity(Gravity.CENTER);
            textView.setTextColor(Theme.getColor(Theme.key_chat_serviceText, resourcesProvider));
            textView.setPadding(dp(12), dp(5), dp(12), dp(6));
            addView(textView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
        }

        public void setDate(int date) {
            updateColors();
            final Calendar messageCalendar = Calendar.getInstance();
            messageCalendar.setTimeInMillis((long) date * 1000);
            final Calendar nowCalendar = Calendar.getInstance();
            if (nowCalendar.get(Calendar.YEAR) == messageCalendar.get(Calendar.YEAR) && nowCalendar.get(Calendar.DAY_OF_YEAR) == messageCalendar.get(Calendar.DAY_OF_YEAR)) {
                textView.setText(getString(R.string.SearchTipToday));
                return;
            }
            nowCalendar.add(Calendar.DAY_OF_YEAR, -1);
            if (nowCalendar.get(Calendar.YEAR) == messageCalendar.get(Calendar.YEAR) && nowCalendar.get(Calendar.DAY_OF_YEAR) == messageCalendar.get(Calendar.DAY_OF_YEAR)) {
                textView.setText(getString(R.string.SearchTipYesterday));
                return;
            }
            textView.setText(LocaleController.formatDateChat(date));
        }

        private void updateColors() {
            textView.setTextColor(Theme.getColor(Theme.key_chat_serviceText, resourcesProvider));
            textView.setBackground(Theme.createRoundRectDrawable(dp(14), Theme.getColor(Theme.key_chat_serviceBackground, resourcesProvider)));
        }

        public static class Factory extends UItem.UItemFactory<FeedDateCell> {
            static {
                setup(new Factory());
            }

            @Override
            public FeedDateCell createView(@NonNull Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
                return new FeedDateCell(context, resourcesProvider);
            }

            @Override
            public void bindView(@NonNull View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
                ((FeedDateCell) view).setDate((Integer) item.object);
            }

            @Override
            public boolean isClickable() {
                return false;
            }

            public static UItem of(int date) {
                final UItem item = UItem.ofFactory(Factory.class);
                item.object = date;
                return item;
            }
        }
    }

    private static class FeedMessage {
        final FeedPost post;
        final MessageObject message;
        final boolean topNear;
        final boolean bottomNear;
        final ChatMessageCell.ChatMessageCellDelegate delegate;

        FeedMessage(FeedPost post, MessageObject message, boolean topNear, boolean bottomNear, ChatMessageCell.ChatMessageCellDelegate delegate) {
            this.post = post;
            this.message = message;
            this.topNear = topNear;
            this.bottomNear = bottomNear;
            this.delegate = delegate;
        }
    }

    private static FeedMessage feedMessageOf(ChatMessageCell cell) {
        return ((FeedMessageCell) cell).feedMessage;
    }

    private static class FeedMessageCell extends ChatMessageCell {

        private FeedMessage feedMessage;

        public FeedMessageCell(Context context, int currentAccount, Theme.ResourcesProvider resourcesProvider) {
            super(context, currentAccount, false, null, resourcesProvider);
            setLayoutParams(new RecyclerView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        public static class Factory extends UItem.UItemFactory<FeedMessageCell> {
            static {
                setup(new Factory());
            }

            @Override
            public FeedMessageCell createView(@NonNull Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
                return new FeedMessageCell(context, currentAccount, resourcesProvider);
            }

            @Override
            public void bindView(@NonNull View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
                final FeedMessageCell cell = (FeedMessageCell) view;
                final FeedMessage feedMessage = (FeedMessage) item.object;
                cell.feedMessage = feedMessage;
                cell.isChat = true;
                cell.forceChannelName = true;
                cell.hasDiscussion = feedMessage.post.hasDiscussion;
                cell.setDelegate(feedMessage.delegate);
                cell.setMessageObject(feedMessage.message, feedMessage.post.group, feedMessage.bottomNear, feedMessage.topNear, false);
            }

            public static UItem of(FeedMessage feedMessage) {
                final UItem item = UItem.ofFactory(Factory.class);
                item.object = feedMessage;
                return item;
            }
        }
    }
}