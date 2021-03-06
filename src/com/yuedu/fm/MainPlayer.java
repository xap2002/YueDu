package com.yuedu.fm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.support.v4.app.FragmentActivity;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.umeng.analytics.MobclickAgent;
import com.yuedu.R;
import com.yuedu.image.ImageCache.ImageCacheParams;
import com.yuedu.image.ImageFetcher;

import org.json.JSONObject;

import java.util.List;
import java.util.Set;

import roboguice.inject.InjectView;

public class MainPlayer extends YueduBaseActivity implements DataAccessor.DataAccessorHandler {


    private BroadcastReceiver mServiceBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Set<String> categories = intent.getCategories();
            if (YueduService.PLAYER_SENDING_BROADCAST_ACTION.equals(action)) {
                if (categories.contains(YueduService.PLAYER_SENDING_BROADCAST_CATEGORY_PLAYER_PLAYING)) {
                    setPlayButtonPlaying(true);
                    Log.d("yuedu","media player is playing!!!!");
                }else if (categories.contains(YueduService.PLAYER_SENDING_BROADCAST_CATEGORY_CURRENT_POSITION)) {
                    long currentPosition = intent.getLongExtra(YueduService.PLAYER_SERVICE_BROADCAST_EXTRA_CURRENT_POSITION_KEY,0);
                    setCurrentPosition((int) currentPosition);
                }else if (categories.contains(YueduService.PLAYER_SENDING_BROADCAST_CATEGORY_PLAYER_PAUSED)) {
                    setPlayButtonPlaying(false);
                    Log.d("yuedu","media player is paused!!!!");
                }else if (categories.contains(YueduService.PLAYER_SENDING_BROADCAST_CATEGORY_PLAYER_STOPPED)) {
                    setPlayButtonPlaying(false);
                    Log.d("yuedu","media player is stopped!!!!");
                }else if (categories.contains(YueduService.PLAYER_SENDING_BROADCAST_CATEGORY_PLAYER_WILL_STOP)) {
                    Log.d("yuedu","media player will stop!!!!");
                }else if (categories.contains(YueduService.PLAYER_SENDING_BROADCAST_CATEGORY_PLAYER_WILL_PLAY)) {
                    Log.d("yuedu","media player will play!!!!");
                }else if (categories.contains(YueduService.PLAYER_SENDING_BROADCAST_CATEGORY_PLAYER_WILL_PAUSE)) {
                    Log.d("yuedu","media player will pause!!!!");
                }else if (categories.contains(YueduService.PLAYER_SENDING_BROADCAST_CATEGORY_PLAYER_WILL_PREPARE)) {
                    Log.d("yuedu","media player will prepare!!!!");
                    setPlayButtonPlaying(true);
                    updateCover();
                    updateListViewSelection();
                    showLoading();
                }else if (categories.contains(YueduService.PLAYER_SENDING_BROADCAST_CATEGORY_PLAYER_ERROR_OCCURRED)) {
                    setPlayButtonPlaying(false);
                    hideLoading();
                    Toast.makeText(getApplicationContext(),intent.getStringExtra(YueduService.PLAYER_SERVICE_BROADCAST_EXTRA_ERROR_KEY),Toast.LENGTH_LONG).show();
                    Log.d("yuedu","media player error occurred!!!!");
                }else if (categories.contains(YueduService.PLAYER_SENDING_BROADCAST_CATEGORY_PLAYER_COMPLETE)) {
                    Log.d("yuedu","media player complete!!!!");
                }else if (categories.contains(YueduService.PLAYER_SENDING_BROADCAST_CATEGORY_PLAYER_PREPARED)) {
                    Log.d("yuedu","media player prepared!!!!");
                    hideLoading();
                }else if (categories.contains(YueduService.PLAYER_SENDING_BROADCAST_CATEGORY_PLAYER_STATE_REPORT)) {
                    boolean isPlaying = intent.getBooleanExtra(YueduService.PLAYER_SERVICE_BROADCAST_EXTRA_PLAYSTATE_KEY,false);
                    Log.d("yuedu","media player state report " + isPlaying +" !!!!");
                    setPlayButtonPlaying(isPlaying);
                }
            }else if (DataAccessor.DATA_ACCESSOR_DOWNLOAD_COMPLETE_ACTION.equals(action)) {
                Log.d("yuedu","data list download complete!!!!");
                updateUI();
            }else if (DataAccessor.DATA_ACCESSOR_DOWNLOAD_FAILED_ACTION.equals(action)) {
                Log.d("yuedu","data list download failed!!!!");
            }
        }
    };

    private void showLoading() {
        Log.d("yuedu","set progress bar indeterminate!!!!");
//        mProgressBar.setIndeterminate(true);
        //do not show progressbar progress animation 2013/09/23
    }

    private void hideLoading() {
        Log.d("yuedu","set progress bar determinate!!!!");
//        mProgressBar.setIndeterminate(false);
        //do not show progressbar progress animation 2013/09/23
    }

    private void setCurrentPosition(int currentPosition) {
        assert currentPosition >= 0;
        mProgressBar.setProgress(currentPosition);
        int positionInSecond = currentPosition/1000;
        int minFirstBit = positionInSecond/600;
        int minSecondBit = positionInSecond%600/60;
        int secFirstBit = positionInSecond%600%60/10;
        int secSecondBit = positionInSecond%600%60%10;
        mPlayedTimeTextView.setText(minFirstBit +""+ minSecondBit + ":" + secFirstBit +""+ secSecondBit);
    }

    private PlaylistAdapter mAdapter;
    private ImageFetcher mImageFetcher;

    @InjectView(R.id.playlist_lv)           private ListView mListView;
    @InjectView(R.id.playlist_ll)           private RelativeLayout mListViewContainer;
    @InjectView(R.id.tune_cover_iv)         private ImageView mImageView;
    @InjectView(R.id.tune_info_tv)          private TextView mInfoView;
    @InjectView(R.id.tune_name_tv)          private TextView mTitleView;
    @InjectView(R.id.playlist_ib)           private ImageButton mListButton;
    @InjectView(R.id.play_ib)               private ImageButton mPlayButton;
    @InjectView(R.id.nexttune_ib)           private ImageButton mNextButton;
    @InjectView(R.id.tune_progress_pb)      private ProgressBar mProgressBar;
    @InjectView(R.id.tune_played_time_tv)   private TextView mPlayedTimeTextView;

    private int getCurrentPlayingTuneDuration() {
        TuneInfo tune = DataAccessor.SINGLE_INSTANCE.getPlayingTune();
        int min = tune.min;
        int sec = tune.sec;
        return (min * 60 + sec)*1000;
    }

    public PlaylistAdapter getAdapter() {
        if (mAdapter == null) {
            mAdapter = new PlaylistAdapter(getApplicationContext(), DataAccessor.SINGLE_INSTANCE.getDataList(), getmImageFetcher());
        }
        return mAdapter;
    }

    public ImageFetcher getmImageFetcher() {
        if (mImageFetcher == null) {
            ImageCacheParams cacheParams = new ImageCacheParams(getApplicationContext(),
                    "image");

            cacheParams.setMemCacheSizePercent(0.6f); // Set memory cache to 60% of app memory
            mImageFetcher = new ImageFetcher(getApplicationContext(), -1);
            mImageFetcher.addImageCache(getSupportFragmentManager(), cacheParams);
        }
        return mImageFetcher;
    }

    private void updateCover() {
        TuneInfo tune = DataAccessor.SINGLE_INSTANCE.getPlayingTune();
        String url = tune.bgURL;
        String title = tune.title;
        String author = tune.author;
        String player = tune.player;
        String info = getString(R.string.author) + author +" "+ getString(R.string.player) + player;
        getmImageFetcher().loadImage(url, mImageView);
        mTitleView.setText(title);
        mInfoView.setText(info);
        mProgressBar.setIndeterminate(false);
        mProgressBar.setMax(getCurrentPlayingTuneDuration());
        mProgressBar.setProgress(0);
        mPlayedTimeTextView.setText("00:00");
    }

    private void updateListView() {
        mListView.setAdapter(getAdapter());
        updateListViewSelection();
    }

    private void updateListViewSelection() {
        int playingIndex = DataAccessor.SINGLE_INSTANCE.getPlayingTuneIndex();
        mListView.setSelection(playingIndex);
        mListView.setItemChecked(playingIndex,true);
    }

    private void updateUI() {
        updateCover();
        updateListView();
    }

    private BroadcastReceiver mNetworkStateReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo info = cm.getActiveNetworkInfo();
            Log.w("yuedu", "Network Type Changed "+info);
            if (info != null && info.getType() == ConnectivityManager.TYPE_WIFI && info.getState() == NetworkInfo.State.CONNECTED) {
                Log.d("yuedu","wifi is connected");
            }else {
                Log.w("yuedu","wifi is disconnected");
                Log.d("yuedu","pause playing");
                pausePlay();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_main_player);
        if (DataAccessor.SINGLE_INSTANCE.getmDataHandler() != this) {
            DataAccessor.SINGLE_INSTANCE.setmDataHandler(this);
        }
        Intent intent = new Intent(getApplicationContext(), YueduService.class);
        startService(intent);
        registerLocalBroadcastReceiver();
        IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(mNetworkStateReceiver, filter);
        mListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                setPlaylistViewVisible(false);
                playTuneAtIndex(position);
            }
        });
        mListButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setPlaylistViewVisible(!isPlaylistViewVisible());
            }
        });
        mPlayButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (playButtonIsPlayingState()) {
                    setPlayButtonPlaying(false);
                    pausePlay();
                }else {
                    setPlayButtonPlaying(true);
                    play();
                }
            }
        });
        mNextButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playNextTune();
            }
        });
        mTitleView.setSelected(true);
        if (DataAccessor.SINGLE_INSTANCE.getDataList().size() > 0) {
            hideGreetingView();
            updateUI();
        }
    }

    @Override
    public void onSuccess(JSONObject jsonObject) {
        hideGreetingView();
    }

    @Override
    public void onFailure(Throwable throwable, JSONObject jsonObject) {
        hideGreetingView();
        Toast.makeText(this,"获取数据失败",Toast.LENGTH_SHORT).show();
    }

    private void hideGreetingView() {
         findViewById(R.id.greeting_view).setVisibility(View.GONE);
    }

    private void setPlaylistViewVisible(boolean visible) {
        int visibility = visible ? View.VISIBLE : View.GONE;
        mListViewContainer.setVisibility(visibility);
    }

    private boolean isPlaylistViewVisible() {
        return mListViewContainer.getVisibility() == View.VISIBLE;
    }

    private boolean playButtonIsPlayingState() {
        return mPlayButton.isSelected();
    }

    private void setPlayButtonPlaying(boolean isPlaying) {
        mPlayButton.setSelected(isPlaying);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterLocalBroadcastReceiver();
    }

    private void play() {
        Intent intent = new Intent(YueduService.PLAYER_RECEIVING_BROADCAST_ACTION);
        intent.addCategory(YueduService.PLAYER_RECEIVING_BROADCAST_CATEGORY_PLAY);
        sendLocalBroadcast(intent);
        setPlayButtonPlaying(true);
    }

    private void pausePlay() {
        Intent intent = new Intent(YueduService.PLAYER_RECEIVING_BROADCAST_ACTION);
        intent.addCategory(YueduService.PLAYER_RECEIVING_BROADCAST_CATEGORY_PAUSE);
        sendLocalBroadcast(intent);
    }

    private void playNextTune() {
        DataAccessor.SINGLE_INSTANCE.playNextTune();
        play();
    }

    private void playTuneAtIndex(int index) {
        DataAccessor.SINGLE_INSTANCE.playTuneAtIndex(index);
        play();
    }

    private void sendLocalBroadcast(Intent intent) {
        assert intent != null;
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
    }

    private void registerLocalBroadcastReceiver() {
        assert mServiceBroadcastReceiver != null;
        IntentFilter filter = new IntentFilter(YueduService.PLAYER_SENDING_BROADCAST_ACTION);
        filter.addCategory(YueduService.PLAYER_SENDING_BROADCAST_CATEGORY_CURRENT_POSITION);
        filter.addCategory(YueduService.PLAYER_SENDING_BROADCAST_CATEGORY_PLAYER_ERROR_OCCURRED);
        filter.addCategory(YueduService.PLAYER_SENDING_BROADCAST_CATEGORY_PLAYER_PLAYING);
        filter.addCategory(YueduService.PLAYER_SENDING_BROADCAST_CATEGORY_PLAYER_PREPARED);
        filter.addCategory(YueduService.PLAYER_SENDING_BROADCAST_CATEGORY_PLAYER_WILL_PREPARE);
        filter.addCategory(YueduService.PLAYER_SENDING_BROADCAST_CATEGORY_PLAYER_STOPPED);
        filter.addCategory(YueduService.PLAYER_SENDING_BROADCAST_CATEGORY_PLAYER_PAUSED);
        filter.addCategory(YueduService.PLAYER_SENDING_BROADCAST_CATEGORY_PLAYER_WILL_STOP);
        filter.addCategory(YueduService.PLAYER_SENDING_BROADCAST_CATEGORY_PLAYER_WILL_PAUSE);
        filter.addCategory(YueduService.PLAYER_SENDING_BROADCAST_CATEGORY_PLAYER_WILL_PLAY);
        filter.addCategory(YueduService.PLAYER_SENDING_BROADCAST_CATEGORY_PLAYER_COMPLETE);
        filter.addCategory(YueduService.PLAYER_SENDING_BROADCAST_CATEGORY_PLAYER_STATE_REPORT);
        LocalBroadcastManager.getInstance(getApplicationContext()).registerReceiver(mServiceBroadcastReceiver,filter);
        IntentFilter dataReceivedFilter = new IntentFilter(DataAccessor.DATA_ACCESSOR_DOWNLOAD_COMPLETE_ACTION);
        LocalBroadcastManager.getInstance(getApplicationContext()).registerReceiver(mServiceBroadcastReceiver, dataReceivedFilter);
        IntentFilter dataFailedFilter = new IntentFilter(DataAccessor.DATA_ACCESSOR_DOWNLOAD_FAILED_ACTION);
        LocalBroadcastManager.getInstance(getApplicationContext()).registerReceiver(mServiceBroadcastReceiver,dataFailedFilter);

    }

    private void unregisterLocalBroadcastReceiver() {
        assert mServiceBroadcastReceiver != null;
        LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(mServiceBroadcastReceiver);
    }

    private void quit() {

        stopService(new Intent(getApplicationContext(),YueduService.class));
        finish();
        MobclickAgent.onKillProcess(this);
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_player, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.options_menu_quit:
                quit();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    static private class PlaylistAdapter extends ArrayAdapter<TuneInfo> {

        private LayoutInflater inflater;
        private ImageFetcher imageFetcher;

        public PlaylistAdapter(Context context, List<TuneInfo> list, ImageFetcher fetcher) {
            super(context, 0, list);
            inflater = (LayoutInflater) context.getSystemService(LAYOUT_INFLATER_SERVICE);
            imageFetcher = fetcher;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TuneInfo tune = getItem(position);
            String url = tune.imgURL;
            String titleStr = tune.title;
            String author = tune.author;
            String player = tune.player;
            String infoStr = getContext().getString(R.string.author) + author +" "+getContext().getString(R.string.player) + player;
            String timeStr = tune.min + ":" + tune.sec;
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.playlist_item, null);
            }
            assert convertView != null;
            ImageView thumb = (ImageView) convertView.findViewById(R.id.tune_item_thumb_iv);
            TextView title = (TextView) convertView.findViewById(R.id.tune_item_title_tv);
            TextView info = (TextView) convertView.findViewById(R.id.tune_item_info_tv);
            TextView time = (TextView) convertView.findViewById(R.id.tune_item_time_tv);
            imageFetcher.loadImage(url, thumb);
            title.setText(titleStr);
            info.setText(infoStr);
            time.setText(timeStr);
            return convertView;
        }
    }

    public class RemoteControlReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) {
                KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                switch (event.getKeyCode()) {
                    case KeyEvent.KEYCODE_MEDIA_PLAY:
                        play();
                        break;
                    case KeyEvent.KEYCODE_MEDIA_PAUSE:
                        pausePlay();
                        break;
                    case KeyEvent.KEYCODE_MEDIA_NEXT:
                        playNextTune();
                        break;
                    default:
                        break;
                }
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        MobclickAgent.onResume(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        MobclickAgent.onPause(this);
    }
}
