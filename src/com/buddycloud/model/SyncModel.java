package com.buddycloud.model;

import java.io.UnsupportedEncodingException;
import java.text.ParseException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.http.entity.StringEntity;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;
import android.util.Log;

import com.buddycloud.http.BuddycloudHTTPHelper;
import com.buddycloud.model.dao.PostsDAO;
import com.buddycloud.model.dao.UnreadCountersDAO;
import com.buddycloud.model.db.PostsTableHelper;
import com.buddycloud.preferences.Preferences;
import com.buddycloud.utils.TimeUtils;

public class SyncModel implements Model<JSONObject, JSONObject, String> {

	private static final String TAG = "PostsModel";
	private static SyncModel instance;
	private static final int PAGE_SIZE = 31;
	private static final String SYNC_ENDPOINT = "/sync";
	private static final String POSTS_ENDPOINT = "/content/posts";
	
	private Map<String, JSONArray> channelsPosts = new HashMap<String, JSONArray>();
	private Map<String, JSONArray> postsComments = new HashMap<String, JSONArray>();
	private Map<String, JSONObject> channelsCounters = new HashMap<String, JSONObject>();
	

	private SyncModel() {}

	
	public static SyncModel getInstance() {
		if (instance == null) {
			instance = new SyncModel();
		}
		return instance;
	}
	
	
	private boolean isPost(JSONObject item) {
		return item.opt("replyTo") == null;
	}
	
	private void parseChannelCounters(UnreadCountersDAO unreadCountersDAO, String channel, JSONArray jsonPosts) {
		JSONObject unreadCounters = channelsCounters.get(channel);
		if (unreadCounters == null) {
			unreadCounters = new JSONObject();
		}

		try {
			unreadCounters.put("totalCount", jsonPosts.length() + unreadCounters.optInt("totalCount"));
			// FIXME: needs to verify if there are mentions
			unreadCounters.put("mentionsCount", 0 + unreadCounters.optInt("mentionsCount"));
		} catch (JSONException e) {/*Do nothing*/}
		
		JSONObject prev = channelsCounters.put(channel, unreadCounters);
		if (prev != null) {
			unreadCountersDAO.update(channel, unreadCounters);
		} else {
			unreadCountersDAO.insert(channel, unreadCounters);
		}
	}
	
	@SuppressWarnings("unchecked")
	private void parse(PostsDAO postsDAO, UnreadCountersDAO unreadCountersDAO, JSONObject response, boolean updateDatabase) {
		Iterator<String> keys = response.keys();
		while (keys.hasNext()) {
			String node = keys.next();
			String channel = node.split("/")[2];
			JSONArray jsonPosts = response.optJSONArray(node);
			parseChannelCounters(unreadCountersDAO, channel, jsonPosts);
			parseChannelPosts(postsDAO, channel, jsonPosts, updateDatabase);
		}
	}
	
	private void parseChannelPosts(PostsDAO postsDAO, String channel, JSONArray jsonPosts, boolean updateDatabase) {
		JSONArray posts = channelsPosts.get(channel);
		if (posts == null) {
			posts = new JSONArray();
		}
		
		for (int i = 0; i < jsonPosts.length(); i++) {
			JSONObject item = jsonPosts.optJSONObject(i);
			String author = item.optString("author");
			
			if (author.contains("acct:")) {
				String[] split = author.split(":");
				author = split[1];
				
				try {
					item.put("author", author);
				} catch (JSONException e) {}
			}
			
			if (updateDatabase) {
				postsDAO.insert(channel, item);
			}
			
			if (isPost(item)) {
				posts.put(item);
			} else {
				String postId = item.optString("replyTo");
				JSONArray comments = postsComments.get(postId);
				if (comments == null) {
					comments = new JSONArray();
				}
				
				comments.put(item);
				postsComments.put(postId, comments);
			}
		}
		
		channelsPosts.put(channel, posts);
	}
	
	private void lookupPostsFromDatabase(PostsDAO postsDAO) {
		List<String> channels = postsDAO.getChannels();
		
		for (String channel : channels) {
			JSONArray response = postsDAO.get(channel, PAGE_SIZE);
			if (response != null && response.length() > 0) {
				parseChannelPosts(postsDAO, channel, response, false);
			}
		}
	}
	
	private void lookupUnreadCountersFromDatabase(UnreadCountersDAO unreadCountersDAO) {
		channelsCounters = unreadCountersDAO.getAll();
	}

	@Override
	public void refresh(Context context, final ModelCallback<JSONObject> callback, String... p) {
		channelsPosts.clear();
		postsComments.clear();
		
		// Lookup for posts at database
		PostsDAO postsDAO = PostsDAO.getInstance(context);
		lookupPostsFromDatabase(postsDAO);
		
		UnreadCountersDAO unreadCountersDAO = UnreadCountersDAO.getInstance(context);
		lookupUnreadCountersFromDatabase(unreadCountersDAO);
		
		// Fetch server
		sync(context, postsDAO, unreadCountersDAO, callback);
	}
	
	private void sync(Context context, final PostsDAO postsDAO, 
			final UnreadCountersDAO unreadCountersDAO, final ModelCallback<JSONObject> callback) {
		BuddycloudHTTPHelper.getObject(syncUrl(context), context,
				new ModelCallback<JSONObject>() {

			@Override
			public void success(JSONObject response) {
				parse(postsDAO, unreadCountersDAO, response, true);
				
				if (callback != null) {
					callback.success(response);
				}
			}

			@Override
			public void error(Throwable throwable) {
				if (callback != null) {
					callback.error(throwable);
				}
			}
		});
	}
	
	private String since() {
		Set<String> channels = channelsPosts.keySet();
		String since = TimeUtils.OLDEST_DATE;
		
		for (String channel : channels) {
			JSONArray posts = channelsPosts.get(channel);
			String temp = null;
			
			if (posts != null) {
				JSONObject mostRecentPost = posts.optJSONObject(0);
				JSONArray comments = postsComments.get(mostRecentPost.optString(PostsTableHelper.COLUMN_ID));
				
				if (comments != null) {
					JSONObject mostRecentComment = comments.optJSONObject(comments.length() - 1);
					temp = mostRecentComment.optString(PostsTableHelper.COLUMN_UPDATED);
				} else {
					temp = mostRecentPost.optString(PostsTableHelper.COLUMN_UPDATED);
				}
				
			}
			
			if (temp != null) {
				try {
					if (TimeUtils.fromISOToDate(since).compareTo(TimeUtils.fromISOToDate(temp)) < 0) {
						since = temp;
					}
				} catch (ParseException e) {/*Do nothing*/}
			}
		}
		
		return since;
	}

	private String syncUrl(Context context) {
		String params = "?max=" + PAGE_SIZE + "&since=" + since();
		
		String apiAddress = Preferences.getPreference(context, Preferences.API_ADDRESS);
		return apiAddress + SYNC_ENDPOINT + params;
	}
	
	private String postsUrl(Context context, String channel) {
		String apiAddress = Preferences.getPreference(context, Preferences.API_ADDRESS);
		return apiAddress + "/" + channel + POSTS_ENDPOINT;
	}

	@Override
	public void save(Context context, JSONObject object,
			ModelCallback<JSONObject> callback, String... p) {
		if (p == null || p.length < 1) {
			return;
		}
		
		try {
			Log.d(TAG, object.toString());
			StringEntity requestEntity = new StringEntity(object.toString(), "UTF-8");
			requestEntity.setContentType("application/json");
			BuddycloudHTTPHelper.post(postsUrl(context, p[0]), true, false, requestEntity, context, callback);
		} catch (UnsupportedEncodingException e) {
			callback.error(e);
		}
	}

	@Override
	public JSONObject get(Context context, String... p) {
		if (p != null && p.length == 1) {
			String channelJid = p[0];
			if (channelsPosts.containsKey(channelJid)) {
				JSONObject json = new JSONObject();
				try {
					json.put(channelJid, channelsPosts.get(channelJid));
					return json;
				} catch (JSONException e) {/*Do nothing*/}
			}
		}
		
		return new JSONObject();
	}
	
	public JSONArray postsFromChannel(String channel) {
		if (channel != null) {
			if (channelsPosts.containsKey(channel)) {
				return channelsPosts.get(channel);
			}
		}
		
		return new JSONArray();
	}
	
	public JSONArray commentsFromPost(String postId) {
		if (postId != null) {
			if (postsComments.containsKey(postId)) {
				return postsComments.get(postId);
			}
		}
		
		return new JSONArray();
	}
	
	public JSONObject countersFromChannel(String channel) {
		if (channel != null) {
			if (channelsCounters.containsKey(channel)) {
				return channelsCounters.get(channel);
			}
		}
		
		return new JSONObject();
	}
	
	public JSONObject postWithId(String postId, String channel) {
		JSONArray jsonArray = postsFromChannel(channel);
		for (int i = 0; i < jsonArray.length(); i++) {
			JSONObject post = jsonArray.optJSONObject(i);
			if (post.optString("id").equals(postId)) {
				return post;
			}
		}
		return new JSONObject();
	}
	
	public void selectChannel(Context context, String channel) {
		
	}
}