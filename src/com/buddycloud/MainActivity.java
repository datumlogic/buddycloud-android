package com.buddycloud;

import org.json.JSONObject;

import android.content.Intent;
import android.os.Bundle;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.view.ViewPager;
import android.support.v4.view.ViewPager.OnPageChangeListener;
import android.util.Log;
import android.view.KeyEvent;

import com.actionbarsherlock.view.Menu;
import com.buddycloud.fragments.ChannelStreamFragment;
import com.buddycloud.fragments.ContentFragment;
import com.buddycloud.fragments.PostDetailsFragment;
import com.buddycloud.fragments.SubscribedChannelsFragment;
import com.buddycloud.model.ModelCallback;
import com.buddycloud.model.SyncModel;
import com.buddycloud.preferences.Preferences;
import com.buddycloud.utils.GCMUtils;
import com.slidingmenu.lib.SlidingMenu;
import com.slidingmenu.lib.SlidingMenu.OnClosedListener;
import com.slidingmenu.lib.SlidingMenu.OnOpenedListener;
import com.slidingmenu.lib.app.SlidingFragmentActivity;

public class MainActivity extends SlidingFragmentActivity {

	private static final String TAG = MainActivity.class.getName();
	private ContentPageAdapter pageAdapter;
	private String myJid;
	private SubscribedChannelsFragment subscribedChannelsFrag;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setBehindContentView(R.layout.menu_frame);
		
		ViewPager viewPager = new ViewPager(this);
		this.pageAdapter = new ContentPageAdapter(getSupportFragmentManager(), viewPager);
		viewPager.setId("VP".hashCode());
		viewPager.setAdapter(pageAdapter);

		viewPager.setOnPageChangeListener(createPageChangeListener());

		setContentView(viewPager);

		if (shouldLogin()) {
			Intent loginActivity = new Intent();
			loginActivity.setClass(getApplicationContext(), LoginActivity.class);
			startActivityForResult(loginActivity, 0);
		} else { 
			startActivity();
		}

		getSupportActionBar().setDisplayHomeAsUpEnabled(true);
	}

	private boolean shouldLogin() {
		//TODO Check credentials here too
		return Preferences.getPreference(this, Preferences.MY_CHANNEL_JID) == null || 
				Preferences.getPreference(this, Preferences.PASSWORD) == null || 
				Preferences.getPreference(this, Preferences.API_ADDRESS) == null;
	}

	@Override
	public boolean onKeyUp(int keyCode, KeyEvent event) {
		if (keyCode == KeyEvent.KEYCODE_BACK) {
			onBackPressed();
			return false;
	    }
	    return true;
	}
	
	@Override
	public void onBackPressed() {
		//TODO Animation between fragments
		if (getSlidingMenu().isMenuShowing()) {
			finish();
		} else if (pageAdapter.getCurrentFragmentIndex() == 0) {
			getSlidingMenu().showMenu();
		} else {
			pageAdapter.setCurrentFragment(pageAdapter.getCurrentFragmentIndex() - 1);
		}
	}
	
	private ContentFragment getCurrentFragment() {
		if (getSlidingMenu().isMenuShowing()) {
			return subscribedChannelsFrag;
		} 
		return pageAdapter.getCurrentFragment();
	}
	
	private OnPageChangeListener createPageChangeListener() {
		return new OnPageChangeListener() {
			@Override
			public void onPageScrollStateChanged(int arg0) { }

			@Override
			public void onPageScrolled(int arg0, float arg1, int arg2) { }

			@Override
			public void onPageSelected(int position) {
				fragmentChanged();
				switch (position) {
				case 0:
					getSlidingMenu().setTouchModeAbove(SlidingMenu.TOUCHMODE_FULLSCREEN);
					break;
				default:
					getSlidingMenu().setTouchModeAbove(SlidingMenu.TOUCHMODE_MARGIN);
					break;
				}
			}
		};
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		startActivity();
	}

	private void startActivity() {
		myJid = (String) Preferences.getPreference(this, Preferences.MY_CHANNEL_JID);
		registerInGCM();
		// Sync with server
		addMenuFragment();
		showChannelFragment(myJid);
		sync();
		customizeMenu();
	}

	private void sync() {
		SyncModel.getInstance().refresh(this, new ModelCallback<JSONObject>() {
			@Override
			public void success(JSONObject response) {
				subscribedChannelsFrag.syncd();
				pageAdapter.getLeftFragment().syncd();
				getSlidingMenu().showMenu();
			}

			@Override
			public void error(Throwable throwable) {
				// TODO Auto-generated method stub
			}
		});
	}

	private void registerInGCM() {
		try {
			GCMUtils.issueGCMRegistration(this);
		} catch (Exception e) {
			Log.e(TAG, "Failed to register in GCM.", e);
		}
	}

	public ChannelStreamFragment showChannelFragment(String channelJid) {
		ChannelStreamFragment myChannelFrag = new ChannelStreamFragment();
		Bundle args = new Bundle();
		args.putString(SubscribedChannelsFragment.CHANNEL, channelJid);
		myChannelFrag.setArguments(args);
		pageAdapter.setLeftFragment(myChannelFrag);
		return myChannelFrag;
	}
	
	public PostDetailsFragment showPostDetailFragment(final String channelJid,
			final String postId) {
		PostDetailsFragment postDetailsFrag = new PostDetailsFragment();
		Bundle args = new Bundle();
		args.putString(PostDetailsFragment.POST_ID, postId);
		args.putString(SubscribedChannelsFragment.CHANNEL, channelJid);
		postDetailsFrag.setArguments(args);
		pageAdapter.setRightFragment(postDetailsFrag);
		return postDetailsFrag;
	}

	private void customizeMenu() {
		SlidingMenu sm = getSlidingMenu();
		sm.setShadowWidthRes(R.dimen.shadow_width);
		sm.setShadowDrawable(R.drawable.shadow);
		sm.setBehindOffsetRes(R.dimen.slidingmenu_offset);
		sm.setFadeDegree(0.15f);
		sm.setTouchModeAbove(SlidingMenu.TOUCHMODE_FULLSCREEN);
		setSlidingActionBarEnabled(false);
		getSlidingMenu().setOnOpenedListener(new OnOpenedListener() {
			@Override
			public void onOpened() {
				fragmentChanged();
			}
		});
		
		getSlidingMenu().setOnClosedListener(new OnClosedListener() {
			@Override
			public void onClosed() {
				fragmentChanged();
			}
		});
	}

	public ContentPageAdapter getPageAdapter() {
		return pageAdapter;
	}

	private void addMenuFragment() {
		FragmentTransaction t = this.getSupportFragmentManager().beginTransaction();
		SubscribedChannelsFragment subscribedFrag = new SubscribedChannelsFragment();
		t.replace(R.id.menu_frame, subscribedFrag);
		t.commitAllowingStateLoss();
		this.subscribedChannelsFrag = subscribedFrag;
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getCurrentFragment().createOptions(menu);
		return super.onCreateOptionsMenu(menu);
	}

	private void fragmentChanged() {
		getCurrentFragment().attached();
		supportInvalidateOptionsMenu();
	}
}
