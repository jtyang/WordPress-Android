package org.wordpress.android.ui.reader.actions;

import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.text.TextUtils;

import com.android.volley.Request;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;
import com.wordpress.rest.RestRequest;

import org.apache.http.HttpStatus;
import org.json.JSONObject;
import org.wordpress.android.WordPress;
import org.wordpress.android.datasets.ReaderBlogTable;
import org.wordpress.android.datasets.ReaderDatabase;
import org.wordpress.android.datasets.ReaderPostTable;
import org.wordpress.android.models.ReaderBlogInfo;
import org.wordpress.android.models.ReaderBlogInfoList;
import org.wordpress.android.models.ReaderFollowedBlogList;
import org.wordpress.android.models.ReaderPost;
import org.wordpress.android.models.ReaderRecommendBlogList;
import org.wordpress.android.ui.reader.ReaderConstants;
import org.wordpress.android.ui.reader.ReaderUtils;
import org.wordpress.android.ui.reader.actions.ReaderActions.UpdateBlogInfoListener;
import org.wordpress.android.ui.reader.actions.ReaderActions.UpdateResult;
import org.wordpress.android.ui.reader.actions.ReaderActions.UpdateResultListener;
import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.AppLog.T;
import org.wordpress.android.util.UrlUtils;
import org.wordpress.android.util.VolleyUtils;
import org.wordpress.android.util.stats.AnalyticsTracker;

import java.util.ArrayList;

public class ReaderBlogActions {

    /*
     * follow/unfollow a blog - make sure to pass the blogId when known since following
     * solely by url may cause the blog to be followed as a feed
     */
    public static boolean performFollowAction(final long blogId,
                                              final String blogUrl,
                                              final boolean isAskingToFollow,
                                              final ReaderActions.ActionListener actionListener) {
        return performFollowAction(blogId, blogUrl, isAskingToFollow, actionListener, true);
    }
    private static boolean performFollowAction(final long blogId,
                                               final String blogUrl,
                                               final boolean isAskingToFollow,
                                               final ReaderActions.ActionListener actionListener,
                                               boolean canLookupBlogInfo) {
        // either blogId or blogUrl are required
        final boolean hasBlogId = (blogId != 0);
        final boolean hasBlogUrl = !TextUtils.isEmpty(blogUrl);
        if (!hasBlogId && !hasBlogUrl) {
            AppLog.w(T.READER, "follow action performed without blogId or blogUrl");
            if (actionListener != null) {
                actionListener.onActionResult(false);
            }
            return false;
        }

        // update local db
        ReaderBlogTable.setIsFollowedBlog(blogId, blogUrl, isAskingToFollow);
        ReaderPostTable.setFollowStatusForPostsInBlog(blogId, blogUrl, isAskingToFollow);

        // if we have the url but not the id, and a lookup hasn't already been performed,
        // lookup the blogInfo to get the id then try again
        if (!hasBlogId && canLookupBlogInfo) {
            lookupBlogIdAndRetryFollow(blogUrl, isAskingToFollow, actionListener);
            return true;
        }

        if (isAskingToFollow) {
            AnalyticsTracker.track(AnalyticsTracker.Stat.READER_FOLLOWED_SITE);
        }

        final String path = getFollowEndpoint(blogId, blogUrl, isAskingToFollow);
        final String actionName = (isAskingToFollow ? "follow" : "unfollow");

        com.wordpress.rest.RestRequest.Listener listener = new RestRequest.Listener() {
            @Override
            public void onResponse(JSONObject jsonObject) {
                boolean success = isFollowActionSuccessful(jsonObject, isAskingToFollow);
                if (success) {
                    AppLog.d(T.READER, "blog " + actionName + " succeeded");
                } else {
                    AppLog.w(T.READER, "blog " + actionName + " failed");
                    localRevertFollowAction(blogId, blogUrl, isAskingToFollow);
                }
                if (actionListener != null) {
                    actionListener.onActionResult(success);
                }
            }
        };
        RestRequest.ErrorListener errorListener = new RestRequest.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError volleyError) {
                AppLog.w(T.READER, "blog " + actionName + " failed");
                AppLog.e(T.READER, volleyError);
                localRevertFollowAction(blogId, blogUrl, isAskingToFollow);
                if (actionListener != null) {
                    actionListener.onActionResult(false);
                }
            }
        };
        WordPress.getRestClientUtils().post(path, listener, errorListener);

        // return before API call completes
        return true;
    }

    /*
     * helper routine when following a blog from a post view
     */
    public static boolean performFollowAction(ReaderPost post,
                                              boolean isAskingToFollow,
                                              ReaderActions.ActionListener actionListener) {
        if (post == null) {
            return false;
        }
        // don't use the blogId if this is an external feed
        long blogId = (post.isExternal ? 0 : post.blogId);
        return performFollowAction(blogId, post.getBlogUrl(), isAskingToFollow, actionListener);
    }

    /*
     * called when a follow/unfollow fails, restores local data to previous state
     */
    private static void localRevertFollowAction(long blogId, String blogUrl, boolean isAskingToFollow) {
        if (blogId == 0 && TextUtils.isEmpty(blogUrl)) {
            return;
        }
        ReaderBlogTable.setIsFollowedBlog(blogId, blogUrl, !isAskingToFollow);
        ReaderPostTable.setFollowStatusForPostsInBlog(blogId, blogUrl, !isAskingToFollow);
    }

    /*
     * returns whether a follow/unfollow was successful based on the response to:
     *      read/follows/new
     *      read/follows/delete
     *      site/$site/follows/new
     *      site/$site/follows/mine/delete
     */
    private static boolean isFollowActionSuccessful(JSONObject json, boolean isAskingToFollow) {
        if (json == null) {
            return false;
        }

        final boolean isSubscribed;
        if (json.has("subscribed")) {
            // read/follows/
            isSubscribed = json.optBoolean("subscribed", false);
        } else if (json.has("is_following")) {
            // site/$site/follows/
            isSubscribed = json.optBoolean("is_following", false);
        } else {
            isSubscribed = false;
        }

        return (isSubscribed == isAskingToFollow);
    }

    /*
     * returns the endpoint path to use when following/unfollowing a blog
     */
    private static String getFollowEndpoint(long blogId, String blogUrl, boolean isAskingToFollow) {
        if (isAskingToFollow) {
            // if we have a blogId, use /sites/$siteId/follows/new - this is important
            // because /read/following/mine/new follows it as a feed rather than a blog,
            // so its posts show up without support for likes, comments, etc.
            if (blogId != 0) {
                return "/sites/" + blogId + "/follows/new";
            } else {
                AppLog.w(T.READER, "following blog by url rather than id");
                return "/read/following/mine/new?url=" + UrlUtils.getDomainFromUrl(blogUrl);
            }
        } else {
            if (blogId != 0) {
                return "/sites/" + blogId + "/follows/mine/delete";
            } else {
                AppLog.w(T.READER, "unfollowing blog by url rather than id");
                return "/read/following/mine/delete?url=" + UrlUtils.getDomainFromUrl(blogUrl);
            }
        }
    }

    /*
     * used when following/unfollowing when the blogId isn't known to attempt to look it up
     * using the blogUrl, then retries following/unfollowing
     */
    private static void lookupBlogIdAndRetryFollow(final String blogUrl,
                                                   final boolean isAskingToFollow,
                                                   final ReaderActions.ActionListener actionListener) {
        ReaderActions.UpdateBlogInfoListener infoListener = new ReaderActions.UpdateBlogInfoListener() {
            @Override
            public void onResult(ReaderBlogInfo blogInfo) {
                if (blogInfo != null) {
                    // we have blogInfo, so follow using id & url from info
                    performFollowAction(blogInfo.blogId, blogInfo.getUrl(), isAskingToFollow, actionListener, false);
                } else {
                    // blogInfo lookup failed, follow using passed url only
                    performFollowAction(0, blogUrl, isAskingToFollow, actionListener, false);
                }
            }
        };
        AppLog.d(T.READER, "looking up blogId for follow by url");
        ReaderBlogActions.updateBlogInfoByUrl(blogUrl, infoListener);
    }

    /*
     * request the list of blogs the current user is following
     */
    public static void updateFollowedBlogs(final UpdateResultListener resultListener) {
        RestRequest.Listener listener = new RestRequest.Listener() {
            @Override
            public void onResponse(JSONObject jsonObject) {
                handleFollowedBlogsResponse(jsonObject, resultListener);
            }
        };
        RestRequest.ErrorListener errorListener = new RestRequest.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError volleyError) {
                AppLog.e(T.READER, volleyError);
                if (resultListener != null) {
                    resultListener.onUpdateResult(UpdateResult.FAILED);
                }
            }
        };
        WordPress.getRestClientUtils().get("/read/following/mine", listener, errorListener);
    }
    private static void handleFollowedBlogsResponse(final JSONObject jsonObject, final UpdateResultListener resultListener) {
        if (jsonObject == null) {
            if (resultListener != null) {
                resultListener.onUpdateResult(UpdateResult.FAILED);
            }
            return;
        }

        final Handler handler = new Handler();
        new Thread() {
            @Override
            public void run() {
                ReaderFollowedBlogList serverBlogs = ReaderFollowedBlogList.fromJson(jsonObject);
                ReaderFollowedBlogList localBlogs = ReaderBlogTable.getFollowedBlogs();

                final boolean hasChanges = !localBlogs.isSameList(serverBlogs);
                if (hasChanges) {
                    ReaderBlogTable.setFollowedBlogs(serverBlogs);
                    // followed blogs have changed, fill in incomplete blogInfos to make sure we have
                    // full info about new blogs
                    updateIncompleteBlogInfo();
                }

                if (resultListener != null) {
                    handler.post(new Runnable() {
                        public void run() {
                            ReaderActions.UpdateResult result = (hasChanges ? UpdateResult.CHANGED : UpdateResult.UNCHANGED);
                            resultListener.onUpdateResult(result);
                        }
                    });
                }
            }
        }.start();
    }

    /*
     * request info about a specific blog
     */
    public static void updateBlogInfo(long blogId,
                                      final String blogUrl,
                                      final UpdateBlogInfoListener infoListener) {
        // must pass either a valid id or url
        final boolean hasBlogId = (blogId != 0);
        final boolean hasBlogUrl = !TextUtils.isEmpty(blogUrl);
        if (!hasBlogId && !hasBlogUrl) {
            AppLog.w(T.READER, "cannot get blog info without either id or url");
            if (infoListener != null) {
                infoListener.onResult(null);
            }
            return;
        }

        RestRequest.Listener listener = new RestRequest.Listener() {
            @Override
            public void onResponse(JSONObject jsonObject) {
                handleUpdateBlogInfoResponse(jsonObject, infoListener);
            }
        };
        RestRequest.ErrorListener errorListener = new RestRequest.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError volleyError) {
                // authentication error may indicate that API access has been disabled for this blog
                int statusCode = VolleyUtils.statusCodeFromVolleyError(volleyError);
                boolean isAuthErr = (statusCode == HttpStatus.SC_FORBIDDEN);
                // if we failed to get the blog info using the id and this isn't an authentication
                // error, try again using just the domain
                if (!isAuthErr && hasBlogId && hasBlogUrl) {
                    AppLog.w(T.READER, "failed to get blog info by id, retrying with url");
                    updateBlogInfo(0, blogUrl, infoListener);
                } else {
                    AppLog.e(T.READER, volleyError);
                    if (infoListener != null) {
                        infoListener.onResult(null);
                    }
                }
            }
        };

        if (hasBlogId) {
            WordPress.getRestClientUtils().get("/sites/" + blogId, listener, errorListener);
        } else {
            String domain = UrlUtils.getDomainFromUrl(UrlUtils.normalizeUrl(blogUrl));
            WordPress.getRestClientUtils().get("/sites/" + domain, listener, errorListener);
        }
    }
    private static void handleUpdateBlogInfoResponse(JSONObject jsonObject, UpdateBlogInfoListener infoListener) {
        if (jsonObject == null) {
            if (infoListener != null) {
                infoListener.onResult(null);
            }
            return;
        }

        ReaderBlogInfo blogInfo = ReaderBlogInfo.fromJson(jsonObject);
        ReaderBlogTable.setBlogInfo(blogInfo);

        if (infoListener != null) {
            infoListener.onResult(blogInfo);
        }
    }

    /*
     * request blogInfo by url only
     */
    private static void updateBlogInfoByUrl(final String blogUrl, final UpdateBlogInfoListener infoListener) {
        updateBlogInfo(0, blogUrl, infoListener);
    }

    /*
     * request the latest recommended blogs, replaces all local ones
     */
    public static void updateRecommendedBlogs(final UpdateResultListener resultListener) {
        RestRequest.Listener listener = new RestRequest.Listener() {
            @Override
            public void onResponse(JSONObject jsonObject) {
                handleRecommendedBlogsResponse(jsonObject, resultListener);
            }
        };
        RestRequest.ErrorListener errorListener = new RestRequest.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError volleyError) {
                AppLog.e(T.READER, volleyError);
                if (resultListener != null) {
                    resultListener.onUpdateResult(UpdateResult.FAILED);
                }
            }
        };

        String path = "/read/recommendations/mine/"
                    + "?source=mobile"
                    + "&number=" + Integer.toString(ReaderConstants.READER_MAX_RECOMMENDED_TO_REQUEST);
        WordPress.getRestClientUtils().get(path, listener, errorListener);
    }
    private static void handleRecommendedBlogsResponse(final JSONObject jsonObject,
                                                       final UpdateResultListener resultListener) {
        if (jsonObject == null) {
            if (resultListener != null) {
                resultListener.onUpdateResult(UpdateResult.FAILED);
            }
            return;
        }

        final Handler handler = new Handler();

        new Thread() {
            @Override
            public void run() {
                ReaderRecommendBlogList serverBlogs = ReaderRecommendBlogList.fromJson(jsonObject);
                ReaderRecommendBlogList localBlogs = ReaderBlogTable.getAllRecommendedBlogs();

                final boolean hasChanges = !localBlogs.isSameList(serverBlogs);
                if (hasChanges) {
                    ReaderBlogTable.setRecommendedBlogs(serverBlogs);
                }

                if (resultListener != null) {
                    handler.post(new Runnable() {
                        public void run() {
                            ReaderActions.UpdateResult result = (hasChanges ? UpdateResult.CHANGED : UpdateResult.UNCHANGED);
                            resultListener.onUpdateResult(result);
                        }
                    });
                }
            }
        }.start();
    }

    /*
     * tests whether the passed url can be reached - does NOT use authentication, and does not
     * account for 404 replacement pages used by ISPs such as Charter
     */
    public static void testBlogUrlReachable(final String blogUrl, final ReaderActions.ActionListener actionListener) {
        // ActionListener is required
        if (actionListener == null) {
            return;
        }
        if (TextUtils.isEmpty(blogUrl)) {
            actionListener.onActionResult(false);
            return;
        }

        Response.Listener<String> listener = new Response.Listener<String>() {
            @Override
            public void onResponse(String response) {
                actionListener.onActionResult(true);
            }
        };
        Response.ErrorListener errorListener = new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError volleyError) {
                AppLog.e(T.READER, volleyError);
                actionListener.onActionResult(false);
            }
        };

        // TODO: this should be a HEAD rather than GET request, but Volley doesn't support HEAD
        StringRequest request = new StringRequest(
                Request.Method.GET,
                blogUrl,
                listener,
                errorListener);
        WordPress.requestQueue.add(request);
    }

    /*
     * fills in information about followed blogs - requests missing info in batches of 25
     */
    private static final int MAX_BATCH_URLS = 25;
    private static void updateIncompleteBlogInfo() {
        // get list of all blogInfos that are incomplete, then remove external (feed) blogs
        // since we know looking them up will fail
        ReaderBlogInfoList incompleteBlogs = ReaderBlogTable.getAllFollowedBlogInfo().getIncompleteList();
        incompleteBlogs.removeExternal();
        if (incompleteBlogs.size() == 0) {
            return;
        }

        // lookup full info in batches
        ArrayList<String> requestUrls = new ArrayList<String>();
        for (ReaderBlogInfo info: incompleteBlogs) {
            requestUrls.add("/sites/" + info.blogId);
            if (requestUrls.size() >= MAX_BATCH_URLS) {
                batchUpdateIncompleteBlogInfo(requestUrls);
                requestUrls.clear();
            }
        }

        // perform the remaining requests
        if (requestUrls.size() > 0) {
            batchUpdateIncompleteBlogInfo(requestUrls);
        }
    }

    private static void batchUpdateIncompleteBlogInfo(ArrayList<String> requestUrls) {
        if (requestUrls == null || requestUrls.size() == 0) {
            return;
        }

        // work with a clone of the passed list so changes to it don't impact us
        final ArrayList<String> copyOfUrls = (ArrayList<String>) requestUrls.clone();

        RestRequest.Listener listener = new RestRequest.Listener() {
            @Override
            public void onResponse(JSONObject jsonObject) {
                if (jsonObject == null) {
                    return;
                }

                SQLiteDatabase db = ReaderDatabase.getWritableDb();
                db.beginTransaction();
                try {
                    int numUpdated = 0;
                    for (String url : copyOfUrls) {
                        // the /batch/ endpoint identifies each response by the requested url
                        JSONObject jsonSite = jsonObject.optJSONObject(url);
                        ReaderBlogInfo blogInfo = ReaderBlogInfo.fromJson(jsonSite);
                        // make sure blogInfo isn't still incomplete before saving it
                        if (!blogInfo.isIncomplete()) {
                            ReaderBlogTable.setBlogInfo(blogInfo);
                            numUpdated++;
                        }
                    }

                    AppLog.d(T.READER, String.format("updated info for %d incomplete blogs", numUpdated));
                    db.setTransactionSuccessful();

                } finally {
                    db.endTransaction();
                }
            }
        };

        RestRequest.ErrorListener errorListener = new RestRequest.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError volleyError) {
                AppLog.e(T.READER, volleyError);
            }
        };

        AppLog.d(T.READER, String.format("requesting info for %d incomplete blogs", copyOfUrls.size()));
        String path = ReaderUtils.getBatchEndpointForRequests(copyOfUrls);
        WordPress.getRestClientUtils().get(path, listener, errorListener);
    }
}
