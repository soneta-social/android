package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
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
        mergedPosts.clear();
        for (int i = 0; i < channelMessages.size(); i++) {
            final ArrayList<FeedPost> posts = channelMessages.valueAt(i);
            if (posts != null) {
                mergedPosts.addAll(posts);
            }
        }
        Collections.sort(mergedPosts, (a, b) -> {
            final int dateA = a.messageObject.messageOwner.date;
            final int dateB = b.messageObject.messageOwner.date;
            if (dateA != dateB) {
                return Integer.compare(dateB, dateA);
            }
            return Integer.compare(b.messageObject.getId(), a.messageObject.getId());
        });
        for (int i = 0; i < mergedPosts.size(); i++) {
            final FeedPost post = mergedPosts.get(i);
            post.chat = feedChannels.get(post.dialogId);
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
                    posts.add(new FeedPost(dialogId, chat, messageObject));
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
            final int date = post.messageObject.messageOwner.date;
            final int dayKey = dayKeyOf(date);
            if (dayKey != lastDayKey) {
                lastDayKey = dayKey;
                items.add(FeedDateCell.Factory.of(date));
            }
            items.add(FeedPostCell.Factory.of(post));
        }
    }

    private void onClick(UItem item, View view, Integer position, Float x, Float y) {
        if (!(item.object instanceof FeedPost)) {
            return;
        }
        final FeedPost post = (FeedPost) item.object;
        if (view instanceof FeedPostCell && ((FeedPostCell) view).isTapOnMedia(x, y)) {
            openPhotoViewer(post);
            return;
        }
        openChat(post);
    }

    private void openPhotoViewer(FeedPost post) {
        if (getParentActivity() == null) {
            return;
        }
        final PhotoViewer photoViewer = PhotoViewer.getInstance();
        photoViewer.setParentActivity(getParentActivity());
        photoViewer.openPhoto(post.messageObject, post.dialogId, 0, 0, new PhotoViewer.EmptyPhotoViewerProvider(), false);
    }

    private void openChat(FeedPost post) {
        Bundle args = new Bundle();
        args.putLong("dialog_id", post.dialogId);
        args.putInt("message_id", post.messageObject.getId());
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
        final MessageObject messageObject;
        TLRPC.Chat chat;

        FeedPost(long dialogId, TLRPC.Chat chat, MessageObject messageObject) {
            this.dialogId = dialogId;
            this.chat = chat;
            this.messageObject = messageObject;
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
        private final TextView messageTextView;
        private final BackupImageView mediaImageView;
        private final TextView viewsTextView;
        private final TextView timeTextView;
        private final Theme.ResourcesProvider resourcesProvider;

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

            messageTextView = new TextView(context);
            messageTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            messageTextView.setMovementMethod(new LinkMovementMethod());
            messageTextView.setTextIsSelectable(false);
            bubbleLayout.addView(messageTextView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 0, 3, 0, 0));

            mediaImageView = new BackupImageView(context);
            mediaImageView.setRoundRadius(dp(8));
            bubbleLayout.addView(mediaImageView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 0, 4, 0, 0));

            final LinearLayout bottomRow = new LinearLayout(context);
            bottomRow.setOrientation(LinearLayout.HORIZONTAL);
            bottomRow.setGravity(Gravity.CENTER_VERTICAL);
            bubbleLayout.addView(bottomRow, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT, 0, 6, 0, 0));

            viewsTextView = new TextView(context);
            viewsTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            bottomRow.addView(viewsTextView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 0, 0));

            timeTextView = new TextView(context);
            timeTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            bottomRow.addView(timeTextView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 8, 0, 0, 0));
        }

        private int getBubbleMaxWidth() {
            final int cellWidth = getMeasuredWidth() > 0 ? getMeasuredWidth() : AndroidUtilities.displaySize.x;
            return cellWidth - dp(12) * 2 - dp(BUBBLE_LEFT_DP) - dp(BUBBLE_RIGHT_DP);
        }

        public void set(FeedPost post) {
            final MessageObject messageObject = post.messageObject;
            updateColors();

            if (post.chat != null) {
                avatarImageView.setForUserOrChat(post.chat, new AvatarDrawable(post.chat, false));
                nameTextView.setText(post.chat.title);
                nameTextView.setTextColor(AvatarDrawable.getColorForId(post.chat.id));
                nameTextView.setVisibility(View.VISIBLE);
            } else {
                nameTextView.setVisibility(View.GONE);
            }
            timeTextView.setText(LocaleController.getInstance().getFormatterDay().format((long) messageObject.messageOwner.date * 1000));

            final int innerMaxWidth = getBubbleMaxWidth() - dp(BUBBLE_PAD_DP * 2);
            nameTextView.setMaxWidth(innerMaxWidth);
            messageTextView.setMaxWidth(innerMaxWidth);

            CharSequence text = messageObject.messageText;
            if (TextUtils.isEmpty(text) && (messageObject.type == MessageObject.TYPE_STICKER || messageObject.type == MessageObject.TYPE_ANIMATED_STICKER)) {
                final String emoji = messageObject.getStickerEmoji();
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

            final boolean hasMedia = (messageObject.type == MessageObject.TYPE_PHOTO || messageObject.type == MessageObject.TYPE_VIDEO || messageObject.type == MessageObject.TYPE_GIF)
                && messageObject.photoThumbs != null && !messageObject.photoThumbs.isEmpty();
            mediaImageView.setVisibility(hasMedia ? View.VISIBLE : View.GONE);
            if (hasMedia) {
                final TLRPC.PhotoSize thumbSize = FileLoader.getClosestPhotoSizeWithSize(messageObject.photoThumbs, 50);
                final TLRPC.PhotoSize photoSize = FileLoader.getClosestPhotoSizeWithSize(messageObject.photoThumbs, dp(560), false, thumbSize, false);
                final TLRPC.PhotoSize smallThumb = photoSize == thumbSize ? null : thumbSize;
                final String imageFilter = "560_360";
                mediaImageView.setImage(
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

                final int mediaWidth = Math.max(dp(80), innerMaxWidth);
                int mediaHeight = dp(210);
                if (photoSize != null && photoSize.w > 0 && photoSize.h > 0) {
                    mediaHeight = (int) ((long) mediaWidth * photoSize.h / photoSize.w);
                    mediaHeight = Math.max(dp(80), Math.min(dp(300), mediaHeight));
                }
                final ViewGroup.LayoutParams mediaParams = mediaImageView.getLayoutParams();
                if (mediaParams instanceof LinearLayout.LayoutParams) {
                    ((LinearLayout.LayoutParams) mediaParams).width = mediaWidth;
                    mediaParams.height = mediaHeight;
                    mediaImageView.setLayoutParams(mediaParams);
                }
            }

            final int views = messageObject.messageOwner.views;
            if (views > 0) {
                viewsTextView.setVisibility(View.VISIBLE);
                viewsTextView.setText(LocaleController.formatShortNumber(views, null));
            } else {
                viewsTextView.setVisibility(View.GONE);
            }
        }

        private void updateColors() {
            bubbleLayout.setBackground(Theme.createRoundRectDrawable(dp(15), Theme.getColor(Theme.key_chat_inBubble, resourcesProvider)));
            messageTextView.setTextColor(Theme.getColor(Theme.key_chat_messageTextIn, resourcesProvider));
            messageTextView.setHighlightColor(Theme.getColor(Theme.key_chat_linkSelectBackground, resourcesProvider));
            viewsTextView.setTextColor(Theme.getColor(Theme.key_chat_inViews, resourcesProvider));
            timeTextView.setTextColor(Theme.getColor(Theme.key_chat_inTimeText, resourcesProvider));
        }

        public boolean isTapOnMedia(float x, float y) {
            if (mediaImageView.getVisibility() != View.VISIBLE) {
                return false;
            }
            final Rect rect = new Rect();
            mediaImageView.getHitRect(rect);
            rect.offset(bubbleLayout.getLeft(), bubbleLayout.getTop());
            return rect.contains((int) x, (int) y);
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
                ((FeedPostCell) view).set((FeedPost) item.object);
            }

            public static UItem of(FeedPost post) {
                final UItem item = UItem.ofFactory(Factory.class);
                item.object = post;
                return item;
            }
        }
    }
}