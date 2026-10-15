package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.LongSparseArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.EmptyTextProgressView;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LinkSpanDrawable;
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
    private static final long REFRESH_INTERVAL_MS = 3 * 60 * 1000L;

    private UniversalRecyclerView listView;
    private EmptyTextProgressView emptyView;
    private FlickerLoadingView flickerLoadingView;
    private SizeNotifierFrameLayout contentView;

    private final HashMap<Long, TLRPC.Chat> feedChannels = new HashMap<>();
    private final LongSparseArray<ArrayList<FeedPost>> channelMessages = new LongSparseArray<>();
    private final HashSet<Long> pendingCacheLoads = new HashSet<>();
    private final HashSet<Long> pendingServerLoads = new HashSet<>();
    private final HashSet<Long> serverRefreshed = new HashSet<>();
    private final ArrayList<FeedPost> mergedPosts = new ArrayList<>();

    private boolean loading = true;
    private long lastFeedLoadTime;
    private boolean dialogsReloadScheduled;
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
        for (int i = 0; i < merged.size(); i++) {
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
            rebuildMerged();
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
            items.add(FeedPostCell.Factory.of(post, v -> openComments(post)));
        }
    }

    private void onClick(UItem item, View view, Integer position, Float x, Float y) {
        if (!(item.object instanceof FeedPost)) {
            return;
        }
        final FeedPost post = (FeedPost) item.object;
        final int mediaIndex = view instanceof FeedPostCell ? ((FeedPostCell) view).getMediaIndexAt(x, y) : -1;
        if (mediaIndex >= 0 && mediaIndex < post.media.size()) {
            openPhotoViewer(post, mediaIndex);
            return;
        }
        openChat(post);
    }

    private void openComments(FeedPost post) {
        final TLRPC.Message message = post.getMainMessage().messageOwner;
        if (message.replies == null || message.replies.channel_id == 0 || message.replies.max_id == 0) {
            openChat(post);
            return;
        }
        final TLRPC.Chat chat = getMessagesController().getChat(-post.dialogId);
        if (chat == null) {
            openChat(post);
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
                openChat(post);
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
                openChat(post);
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

    private void openChat(FeedPost post) {
        Bundle args = new Bundle();
        args.putLong("dialog_id", post.dialogId);
        args.putInt("message_id", post.getMainMessage().getId());
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
        if (listView != null) {
            listView.smoothScrollToPosition(0);
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
        CharSequence caption;
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
            caption = null;
            int maxId = 0;
            for (int i = 0; i < messages.size(); i++) {
                final MessageObject messageObject = messages.get(i);
                maxId = Math.max(maxId, messageObject.getId());
                if (TextUtils.isEmpty(caption) && !TextUtils.isEmpty(messageObject.caption)) {
                    caption = messageObject.caption;
                }
                if (isMediaMessage(messageObject)) {
                    media.add(messageObject);
                }
            }
            sortId = maxId;
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

    private static class FeedPostCell extends FrameLayout {

        private static final int BUBBLE_LEFT_DP = 42;
        private static final int BUBBLE_RIGHT_DP = 48;
        private static final int BUBBLE_PAD_DP = 10;

        private final BackupImageView avatarImageView;
        private final LinearLayout bubbleLayout;
        private final TextView nameTextView;
        private final LinkSpanDrawable.LinksTextView messageTextView;
        private final FrameLayout mediaContainer;
        private final ArrayList<BackupImageView> mediaCells = new ArrayList<>();
        private final LinearLayout commentsButton;
        private final View commentsDivider;
        private final ImageView commentsButtonIcon;
        private final TextView commentsButtonTextView;
        private final ImageView viewsIconImageView;
        private final TextView viewsTextView;
        private final TextView timeTextView;
        private final Theme.ResourcesProvider resourcesProvider;
        private View.OnClickListener commentsClickListener;

        public FeedPostCell(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.resourcesProvider = resourcesProvider;
            setLayoutParams(new RecyclerView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            setPadding(dp(12), dp(4), dp(12), dp(4));

            avatarImageView = new BackupImageView(context);
            avatarImageView.setRoundRadius(dp(17));
            addView(avatarImageView, LayoutHelper.createFrame(34, 34, Gravity.TOP | Gravity.LEFT, 0, 8, 0, 0));

            bubbleLayout = new LinearLayout(context);
            bubbleLayout.setOrientation(LinearLayout.VERTICAL);
            bubbleLayout.setPadding(dp(BUBBLE_PAD_DP), dp(7), dp(BUBBLE_PAD_DP), dp(7));
            addView(bubbleLayout, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, BUBBLE_LEFT_DP, 8, BUBBLE_RIGHT_DP, 0));

            nameTextView = new TextView(context);
            nameTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            nameTextView.setTypeface(AndroidUtilities.bold());
            nameTextView.setMaxLines(1);
            nameTextView.setEllipsize(TextUtils.TruncateAt.END);
            bubbleLayout.addView(nameTextView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT));

            messageTextView = new LinkSpanDrawable.LinksTextView(context, resourcesProvider);
            messageTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            messageTextView.setTextIsSelectable(false);
            bubbleLayout.addView(messageTextView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 0, 3, 0, 0));

            mediaContainer = new FrameLayout(context);
            bubbleLayout.addView(mediaContainer, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 0, 4, 0, 0));

            final LinearLayout bottomRow = new LinearLayout(context);
            bottomRow.setOrientation(LinearLayout.HORIZONTAL);
            bottomRow.setGravity(Gravity.CENTER_VERTICAL);
            bubbleLayout.addView(bottomRow, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT, 0, 6, 0, 0));

            viewsIconImageView = new ImageView(context);
            bottomRow.addView(viewsIconImageView, LayoutHelper.createLinear(12, 12, Gravity.CENTER_VERTICAL));

            viewsTextView = new TextView(context);
            viewsTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            bottomRow.addView(viewsTextView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 3, 0, 0, 0));

            timeTextView = new TextView(context);
            timeTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            bottomRow.addView(timeTextView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 8, 0, 0, 0));

            // comments plate like in channels: divider + icon + "N comments"
            commentsButton = new LinearLayout(context);
            commentsButton.setOrientation(LinearLayout.VERTICAL);
            commentsButton.setOnClickListener(v -> {
                if (commentsClickListener != null) {
                    commentsClickListener.onClick(commentsButton);
                }
            });
            commentsDivider = new View(context);
            commentsButton.addView(commentsDivider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, Gravity.TOP, 0, 4, 0, 0));

            final LinearLayout commentsRow = new LinearLayout(context);
            commentsRow.setOrientation(LinearLayout.HORIZONTAL);
            commentsRow.setGravity(Gravity.CENTER_VERTICAL);
            commentsRow.setPadding(dp(9), dp(6), dp(9), dp(4));
            commentsButton.addView(commentsRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            commentsButtonIcon = new ImageView(context);
            commentsRow.addView(commentsButtonIcon, LayoutHelper.createLinear(16, 16, Gravity.CENTER_VERTICAL));

            commentsButtonTextView = new TextView(context);
            commentsButtonTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            commentsButtonTextView.setTypeface(AndroidUtilities.bold());
            commentsRow.addView(commentsButtonTextView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 8, 0, 0, 0));
        }

        private int getBubbleMaxWidth() {
            final int cellWidth = getMeasuredWidth() > 0 ? getMeasuredWidth() : AndroidUtilities.displaySize.x;
            return cellWidth - dp(12) * 2 - dp(BUBBLE_LEFT_DP) - dp(BUBBLE_RIGHT_DP);
        }

        public void set(FeedPost post, View.OnClickListener onCommentsClick) {
            final MessageObject mainMessage = post.getMainMessage();
            commentsClickListener = onCommentsClick;
            updateColors();

            if (post.chat != null) {
                avatarImageView.setForUserOrChat(post.chat, new AvatarDrawable(post.chat, false));
                nameTextView.setText(post.chat.title);
                nameTextView.setTextColor(AvatarDrawable.getColorForId(post.chat.id));
                nameTextView.setVisibility(View.VISIBLE);
            } else {
                nameTextView.setVisibility(View.GONE);
            }
            timeTextView.setText(LocaleController.getInstance().getFormatterDay().format((long) mainMessage.messageOwner.date * 1000));

            final int innerMaxWidth = getBubbleMaxWidth() - dp(BUBBLE_PAD_DP * 2);
            final int mediaWidth = updateMedia(post, innerMaxWidth);
            final int textMaxWidth = mediaWidth > 0 ? Math.min(innerMaxWidth, mediaWidth) : innerMaxWidth;
            nameTextView.setMaxWidth(textMaxWidth);
            messageTextView.setMaxWidth(textMaxWidth);

            CharSequence text = post.caption;
            if (TextUtils.isEmpty(text)) {
                for (int i = 0; i < post.messages.size(); i++) {
                    final CharSequence messageText = post.messages.get(i).messageText;
                    if (!TextUtils.isEmpty(messageText)) {
                        text = messageText;
                        break;
                    }
                }
            }
            if (TextUtils.isEmpty(text) && (mainMessage.type == MessageObject.TYPE_STICKER || mainMessage.type == MessageObject.TYPE_ANIMATED_STICKER)) {
                final String emoji = mainMessage.getStickerEmoji();
                if (!TextUtils.isEmpty(emoji)) {
                    text = emoji;
                }
            }
            if (TextUtils.isEmpty(text)) {
                messageTextView.setVisibility(View.GONE);
            } else {
                messageTextView.setVisibility(View.VISIBLE);
                messageTextView.setText(text);
            }

            // in channels the caption goes below the media
            if (mediaWidth > 0 && messageTextView.getVisibility() == View.VISIBLE) {
                if (bubbleLayout.indexOfChild(messageTextView) < bubbleLayout.indexOfChild(mediaContainer)) {
                    bubbleLayout.removeView(messageTextView);
                    bubbleLayout.addView(messageTextView, bubbleLayout.indexOfChild(mediaContainer) + 1);
                }
            } else if (bubbleLayout.indexOfChild(messageTextView) > bubbleLayout.indexOfChild(mediaContainer)) {
                bubbleLayout.removeView(messageTextView);
                bubbleLayout.addView(messageTextView, bubbleLayout.indexOfChild(nameTextView) + 1);
            }

            int views = 0;
            int replies = 0;
            boolean hasDiscussion = false;
            for (int i = 0; i < post.messages.size(); i++) {
                views = Math.max(views, post.messages.get(i).messageOwner.views);
                replies = Math.max(replies, post.messages.get(i).getRepliesCount());
                final TLRPC.Message m = post.messages.get(i).messageOwner;
                if (m.replies != null && m.replies.channel_id != 0) {
                    hasDiscussion = true;
                }
            }
            if (hasDiscussion && Theme.chat_commentDrawable != null) {
                if (commentsButtonIcon.getDrawable() == null) {
                    commentsButtonIcon.setImageDrawable(Theme.chat_commentDrawable);
                }
                commentsButton.setVisibility(View.VISIBLE);
                commentsButtonTextView.setText(replies > 0 ? LocaleController.formatPluralString("CommentsCount", replies) : getString(R.string.LeaveAComment));
            } else {
                commentsButton.setVisibility(View.GONE);
            }
            if (views > 0 && Theme.chat_msgInViewsDrawable != null) {
                if (viewsIconImageView.getDrawable() == null) {
                    viewsIconImageView.setImageDrawable(Theme.chat_msgInViewsDrawable);
                }
                viewsIconImageView.setVisibility(View.VISIBLE);
                viewsTextView.setVisibility(View.VISIBLE);
                viewsTextView.setText(LocaleController.formatShortNumber(views, null));
            } else {
                viewsIconImageView.setVisibility(View.GONE);
                viewsTextView.setVisibility(View.GONE);
            }
        }

        // returns the drawn media width (0 when no media), layout is the same as in ChatMessageCell
        private int updateMedia(FeedPost post, int innerMaxWidth) {
            final int count = post.media.size();
            mediaContainer.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
            if (count == 0) {
                return 0;
            }
            final int groupWidth = Math.max(dp(80), innerMaxWidth);

            if (count == 1) {
                ensureMediaCells(1);
                final int height = mediaAspectHeight(post.media.get(0), groupWidth, dp(80), dp(300));
                layoutMediaCell(0, 0, 0, groupWidth, height, post.media.get(0), dp(8), dp(8), dp(8), dp(8));
                setMediaContainerSize(groupWidth, height);
                return groupWidth;
            }

            final MessageObject.GroupedMessages grouped = new MessageObject.GroupedMessages();
            grouped.groupId = post.getMainMessage().getGroupId();
            grouped.messages.addAll(post.media);
            grouped.calculate();

            final float maxHeight = Math.max(getMeasuredWidth() > 0 ? getMeasuredWidth() : AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y) * 0.5f;

            final HashMap<Integer, Float> rowHeights = new HashMap<>();
            for (int i = 0; i < post.media.size(); i++) {
                final MessageObject.GroupedMessagePosition pos = grouped.getPosition(post.media.get(i));
                if (pos == null || pos.minY != pos.maxY) {
                    continue;
                }
                final Float prev = rowHeights.get((int) pos.minY);
                if (prev == null || pos.ph > prev) {
                    rowHeights.put((int) pos.minY, pos.ph);
                }
            }

            ensureMediaCells(count);
            int maxRight = 0;
            int maxBottom = 0;
            for (int i = 0; i < post.media.size(); i++) {
                final MessageObject messageObject = post.media.get(i);
                final MessageObject.GroupedMessagePosition pos = grouped.getPosition(messageObject);
                if (pos == null) {
                    continue;
                }
                final int w = (int) Math.ceil(pos.pw / 1000.0f * groupWidth);
                int h = 0;
                if (pos.siblingHeights != null) {
                    h = 0;
                    for (int a = 0; a < pos.siblingHeights.length; a++) {
                        h += (int) Math.ceil(maxHeight * pos.siblingHeights[a]);
                    }
                    h += (pos.maxY - pos.minY) * Math.round(dp(7));
                } else {
                    h = (int) Math.ceil(maxHeight * pos.ph);
                }
                final int x;
                if (pos.leftSpanOffset != 0) {
                    x = (int) Math.ceil(pos.leftSpanOffset / 1000.0f * groupWidth);
                } else {
                    int left = 0;
                    for (int j = 0; j < post.media.size(); j++) {
                        if (j == i) {
                            continue;
                        }
                        final MessageObject.GroupedMessagePosition other = grouped.getPosition(post.media.get(j));
                        if (other != null && other.minY <= pos.minY && pos.minY <= other.maxY && other.maxX < pos.minX) {
                            left += other.pw;
                        }
                    }
                    x = (int) Math.ceil(left / 1000.0f * groupWidth);
                }
                int y = 0;
                for (int row = 0; row < pos.minY; row++) {
                    final Float rowHeight = rowHeights.get(row);
                    if (rowHeight != null) {
                        y += (int) Math.ceil(maxHeight * rowHeight);
                    }
                }

                final int minRad = dp(4);
                final int rad = dp(8);
                int tl = rad, tr = rad, br = rad, bl = rad;
                if ((pos.flags & MessageObject.POSITION_FLAG_RIGHT) == 0) {
                    tr = br = minRad;
                }
                if ((pos.flags & MessageObject.POSITION_FLAG_LEFT) == 0) {
                    tl = bl = minRad;
                }
                if ((pos.flags & MessageObject.POSITION_FLAG_BOTTOM) == 0) {
                    bl = br = minRad;
                }
                if ((pos.flags & MessageObject.POSITION_FLAG_TOP) == 0) {
                    tl = tr = minRad;
                }
                layoutMediaCell(i, x, y, w, h, messageObject, tl, tr, br, bl);
                maxRight = Math.max(maxRight, x + w);
                maxBottom = Math.max(maxBottom, y + h);
            }
            setMediaContainerSize(maxRight, maxBottom);
            return maxRight;
        }

        private void ensureMediaCells(int count) {
            while (mediaCells.size() < count) {
                final BackupImageView imageView = new BackupImageView(getContext());
                imageView.setRoundRadius(0);
                mediaContainer.addView(imageView);
                mediaCells.add(imageView);
            }
            for (int i = mediaCells.size() - 1; i >= count; i--) {
                final BackupImageView removed = mediaCells.remove(i);
                removed.getImageReceiver().clearImage();
                mediaContainer.removeView(removed);
            }
        }

        private void layoutMediaCell(int index, int x, int y, int width, int height, MessageObject messageObject, int tl, int tr, int br, int bl) {
            final BackupImageView cell = mediaCells.get(index);
            final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height, Gravity.TOP | Gravity.LEFT);
            params.leftMargin = x;
            params.topMargin = y;
            cell.setLayoutParams(params);
            cell.getImageReceiver().setRoundRadius(tl, tr, br, bl);
            setImage(cell, messageObject, width, height);
        }

        private void setMediaContainerSize(int width, int height) {
            final ViewGroup.LayoutParams params = mediaContainer.getLayoutParams();
            if (params instanceof LinearLayout.LayoutParams) {
                ((LinearLayout.LayoutParams) params).width = width;
                params.height = height;
                mediaContainer.setLayoutParams(params);
            }
        }

        private static void setImage(BackupImageView imageView, MessageObject messageObject, int width, int height) {
            final TLRPC.PhotoSize thumbSize = FileLoader.getClosestPhotoSizeWithSize(messageObject.photoThumbs, 50);
            final TLRPC.PhotoSize photoSize = FileLoader.getClosestPhotoSizeWithSize(messageObject.photoThumbs, Math.max(width, height), false, thumbSize, false);
            final TLRPC.PhotoSize smallThumb = photoSize == thumbSize ? null : thumbSize;
            final String imageFilter = width + "_" + height;
            imageView.setImage(
                ImageLocation.getForObject(photoSize, messageObject.photoThumbsObject),
                imageFilter,
                smallThumb != null ? ImageLocation.getForObject(smallThumb, messageObject.photoThumbsObject) : null,
                imageFilter + "_b",
                messageObject.strippedThumb,
                null,
                null,
                photoSize != null ? photoSize.size : 0,
                messageObject.photoThumbsObject
            );
        }

        private static int mediaAspectHeight(MessageObject messageObject, int width, int minHeight, int maxHeight) {
            final TLRPC.PhotoSize photoSize = FileLoader.getClosestPhotoSizeWithSize(messageObject.photoThumbs, 800);
            int height;
            if (photoSize != null && photoSize.w > 0 && photoSize.h > 0) {
                height = (int) ((long) width * photoSize.h / photoSize.w);
            } else {
                height = width * 3 / 4;
            }
            return Math.max(minHeight, Math.min(maxHeight, height));
        }

        private void updateColors() {
            bubbleLayout.setBackground(Theme.createRoundRectDrawable(dp(15), Theme.getColor(Theme.key_chat_inBubble, resourcesProvider)));
            messageTextView.setTextColor(Theme.getColor(Theme.key_chat_messageTextIn, resourcesProvider));
            commentsDivider.setBackgroundColor(Theme.getColor(Theme.key_chat_inReplyLine, resourcesProvider));
            commentsButtonTextView.setTextColor(Theme.getColor(Theme.key_chat_inPreviewInstantText, resourcesProvider));
            viewsTextView.setTextColor(Theme.getColor(Theme.key_chat_inViews, resourcesProvider));
            timeTextView.setTextColor(Theme.getColor(Theme.key_chat_inTimeText, resourcesProvider));
        }

        public int getMediaIndexAt(float x, float y) {
            if (mediaContainer.getVisibility() != View.VISIBLE) {
                return -1;
            }
            final Rect rect = new Rect();
            mediaContainer.getHitRect(rect);
            rect.offset(bubbleLayout.getLeft(), bubbleLayout.getTop());
            if (!rect.contains((int) x, (int) y)) {
                return -1;
            }
            final float localX = x - rect.left;
            final float localY = y - rect.top;
            for (int i = 0; i < mediaCells.size(); i++) {
                final View cell = mediaCells.get(i);
                if (localX >= cell.getLeft() && localX <= cell.getRight() && localY >= cell.getTop() && localY <= cell.getBottom()) {
                    return i;
                }
            }
            return -1;
        }

        public static class Factory extends UItem.UItemFactory<FeedPostCell> {
            static {
                setup(new Factory());
            }

            @Override
            public FeedPostCell createView(@NonNull Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
                return new FeedPostCell(context, resourcesProvider);
            }

            @Override
            public void bindView(@NonNull View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
                ((FeedPostCell) view).set((FeedPost) item.object, item.clickCallback);
            }

            public static UItem of(FeedPost post, View.OnClickListener onCommentsClick) {
                final UItem item = UItem.ofFactory(Factory.class);
                item.object = post;
                item.clickCallback = onCommentsClick;
                return item;
            }
        }
    }
}