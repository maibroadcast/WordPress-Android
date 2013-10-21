package org.wordpress.android.ui.stats;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.content.LocalBroadcastManager;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.TextView;
import android.widget.Toast;

import com.actionbarsherlock.app.ActionBar;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuInflater;
import com.actionbarsherlock.view.MenuItem;
import com.android.volley.VolleyError;

import org.json.JSONException;
import org.json.JSONObject;

import org.wordpress.android.R;
import org.wordpress.android.WordPress;
import org.wordpress.android.models.Blog;
import org.wordpress.android.ui.AuthenticatedWebViewActivity;
import org.wordpress.android.ui.WPActionBarActivity;
import org.wordpress.android.util.StatsRestHelper;
import org.xmlrpc.android.ApiHelper;

/**
 * The native stats activity, accessible via the menu drawer.
 * This activity is for phone layout, see {@link StatsActivityTablet} for the tablet version. 
 * <p>
 * By pressing a spinner on the action bar, the user can select which stats view they wish to see.
 * </p>
 */
public class StatsActivity extends WPActionBarActivity implements StatsNavDialogFragment.NavigationListener {

    private static final String SAVED_NAV_POSITION = "SAVED_NAV_POSITION";
    private static final String SAVED_WP_LOGIN_STATE = "SAVED_WP_LOGIN_STATE";
    private static final int REQUEST_JETPACK = 7000;
    
    private StatsAbsViewFragment mStatsViewFragment;
    private View mActionbarNav;
    private TextView mActionbarNavText;
    private DialogFragment mNavFragment;
    private Dialog mSignInDialog;
    private int mNavPosition = 0;

    private MenuItem mRefreshMenuItem;
    private int mResultCode = -1;
    private boolean mIsRestoredFromState = false;
    
    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(StatsRestHelper.REFRESH_VIEW_TYPE)) {
                
                if (mRefreshMenuItem == null)
                    return;
                
                // stop or start animating refresh button depending on result
                boolean started = intent.getBooleanExtra(StatsRestHelper.REFRESH_VIEW_TYPE_STARTED, false);
                int ordinal = intent.getIntExtra(StatsRestHelper.REFRESH_VIEW_TYPE_ORDINAL, -1);
                if (ordinal == -1 && !started) {
                    stopAnimatingRefreshButton(mRefreshMenuItem);
                } else if (mStatsViewFragment != null && mStatsViewFragment.getViewType().ordinal() == ordinal) {
                    if (started)
                        startAnimatingRefreshButton(mRefreshMenuItem);
                    else
                        stopAnimatingRefreshButton(mRefreshMenuItem);
                            
                }
            }
        }
    };
    
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (WordPress.wpDB == null) {
            Toast.makeText(this, R.string.fatal_db_error, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        setTitle("");

        createMenuDrawer(R.layout.stats_activity);

        final ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayShowCustomEnabled(true);
        actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_STANDARD);
        
        restoreState(savedInstanceState);
        
        mActionbarNav = getLayoutInflater().inflate(R.layout.stats_ab_navigation, null, false);
        actionBar.setCustomView(mActionbarNav);
        
        mActionbarNavText = (TextView) mActionbarNav.findViewById(R.id.stats_ab_nav_text);
        mActionbarNavText.setText(StatsViewType.getImplemented()[mNavPosition].getLabel());
        mActionbarNavText.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                if (!isFinishing())
                    showViews();
            }

        });
        
        FragmentManager fm = getSupportFragmentManager();
        mStatsViewFragment = (StatsAbsViewFragment) fm.findFragmentByTag(StatsAbsViewFragment.TAG);
        if (mStatsViewFragment == null) { 
            mStatsViewFragment = StatsAbsViewFragment.newInstance(StatsViewType.getImplemented()[0]);
            fm.beginTransaction().add(R.id.stats_container, mStatsViewFragment, StatsAbsViewFragment.TAG).commit();
        }
        
        mNavFragment = (DialogFragment) fm.findFragmentByTag(StatsNavDialogFragment.TAG);
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        
        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        lbm.registerReceiver(mReceiver, new IntentFilter(StatsRestHelper.REFRESH_VIEW_TYPE));
        
        // for self-hosted sites; launch the user into an activity where they can provide their credentials 
        if (!WordPress.getCurrentBlog().hasValidJetpackCredentials() && mResultCode != RESULT_CANCELED) {
            startWPComLoginActivity();
            return;
        }
        
        if (!mIsRestoredFromState)
            refreshStats();
    }

    private void startWPComLoginActivity() {
        mResultCode = RESULT_CANCELED;
        Intent loginIntent = new Intent(this, WPComLoginActivity.class);
        loginIntent.putExtra(WPComLoginActivity.JETPACK_AUTH_REQUEST, true);
        startActivityForResult(loginIntent, WPComLoginActivity.REQUEST_CODE);
    }
    
    @Override
    protected void onPause() {
        super.onPause();

        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        lbm.unregisterReceiver(mReceiver);
        
        stopAnimatingRefreshButton(mRefreshMenuItem);
    }

    private void restoreState(Bundle savedInstanceState) {
        if (savedInstanceState == null)
            return;
            
        mNavPosition = savedInstanceState.getInt(SAVED_NAV_POSITION);
        mResultCode = savedInstanceState.getInt(SAVED_WP_LOGIN_STATE);
        mIsRestoredFromState = true;
    }
    
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        
        outState.putInt(SAVED_NAV_POSITION, mNavPosition);
        outState.putInt(SAVED_WP_LOGIN_STATE, mResultCode);
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == WPComLoginActivity.REQUEST_CODE) {
            
            mResultCode = resultCode;
            
            if (resultCode == RESULT_OK)
                refreshStats();
        }
    }

    private void verifyJetpackSettings() {
        new ApiHelper.RefreshBlogContentTask(this, WordPress.getCurrentBlog(), new ApiHelper.RefreshBlogContentTask.Callback() {
            @Override
            public void onSuccess() {
                if (getBlogIdFromJetpack() == null) {
                    // Blog has not returned a jetpack_client_id
                    AlertDialog.Builder builder = new AlertDialog.Builder(StatsActivity.this);
                    builder.setMessage(getString(R.string.jetpack_message))
                            .setTitle(getString(R.string.jetpack_not_found));
                    builder.setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            Intent jetpackIntent = new Intent(StatsActivity.this, AuthenticatedWebViewActivity.class);
                            jetpackIntent.putExtra(AuthenticatedWebViewActivity.LOAD_AUTHENTICATED_URL, WordPress.getCurrentBlog().getAdminUrl()
                                    + "plugin-install.php?tab=search&s=jetpack+by+wordpress.com&plugin-search-input=Search+Plugins");
                            startActivityForResult(jetpackIntent, REQUEST_JETPACK);
                        }
                    });
                    builder.setNegativeButton(R.string.no, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            // User cancelled the dialog
                        }
                    });
                    builder.create().show();
                }
            }

            @Override
            public void onFailure() {

            }
        }).execute(false);
    }

    protected void showViews() {
        FragmentManager fm = getSupportFragmentManager();
        mNavFragment = (DialogFragment) fm.findFragmentByTag(StatsNavDialogFragment.TAG);
        if (mNavFragment == null)
            mNavFragment = StatsNavDialogFragment.newInstance(mNavPosition);
        else if (mNavFragment.getDialog().isShowing())
            return;
            
        if (!mNavFragment.isVisible())
            mNavFragment.show(getSupportFragmentManager(), StatsNavDialogFragment.TAG);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        MenuInflater inflater = getSupportMenuInflater();
        inflater.inflate(R.menu.stats, menu);
        mRefreshMenuItem = menu.findItem(R.id.menu_refresh);
        return true;
    }
    
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_refresh) {
            refreshStats();
            return true;
        } else if (item.getItemId() == R.id.menu_view_stats_full_site) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://wordpress.com/my-stats")));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
    
    @Override
    public void onItemClick(int position) {
        mNavPosition = position;
        StatsViewType viewType = StatsViewType.getImplemented()[mNavPosition];
        mActionbarNavText.setText(viewType.getLabel());

        FragmentManager fm = getSupportFragmentManager();
        StatsNavDialogFragment navFragment = (StatsNavDialogFragment) fm.findFragmentByTag(StatsNavDialogFragment.TAG);
        if (navFragment != null)
            navFragment.dismissAllowingStateLoss();
        
        mStatsViewFragment = StatsAbsViewFragment.newInstance(viewType);
        fm.beginTransaction().replace(R.id.stats_container, mStatsViewFragment, StatsAbsViewFragment.TAG).commit();
        refreshStats();
    }

    @Override
    public void onBlogChanged() {
        super.onBlogChanged();
        refreshStats();
    }

    private void refreshStats() {
        if (WordPress.getCurrentBlog() == null)
            return;
        
        String blogId = null;
        
        if (WordPress.getCurrentBlog().isDotcomFlag())
            blogId = String.valueOf(WordPress.getCurrentBlog().getBlogId());
        else  {
            blogId = getBlogIdFromJetpack();
            if (blogId == null) {
                verifyJetpackSettings();
            }
        }

        StatsRestHelper.getStatsSummary(blogId, new StatsRestHelper.StatsSummaryInterface() {
            @Override
            public void onSuccess() {
            }

            @Override
            public void onFailure(VolleyError error) {
                if (mSignInDialog != null && mSignInDialog.isShowing())
                    return;

                if (!isFinishing() && WordPress.getCurrentBlog().isJetpackPowered() &&
                        error.networkResponse != null && error.networkResponse.statusCode == 403) {
                    // This Jetpack site has the wrong WP.com credentials
                    AlertDialog.Builder builder = new AlertDialog.Builder(StatsActivity.this);
                    builder.setTitle(getString(R.string.jetpack_stats_unauthorized))
                            .setMessage(getString(R.string.jetpack_stats_switch_user));
                    builder.setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            startActivityForResult(new Intent(StatsActivity.this, WPComLoginActivity.class), WPComLoginActivity.REQUEST_CODE);
                        }
                    });
                    builder.setNegativeButton(R.string.no, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            // User cancelled the dialog
                        }
                    });
                    mSignInDialog = builder.create();
                    mSignInDialog.show();
                }
            }
        });
        
        if (mStatsViewFragment != null) {
            StatsViewType viewType = mStatsViewFragment.getViewType();
            StatsRestHelper.getStats(viewType, blogId);
        }
    }

    private String getBlogIdFromJetpack() {
        // for self-hosted blogs
        try {
            Blog currentBlog = WordPress.getCurrentBlog();
            String jetpackBlogId = currentBlog.getApi_blogid();
            if (jetpackBlogId == null) {
                JSONObject options = new JSONObject(WordPress.getCurrentBlog().getBlogOptions());
                jetpackBlogId = options.getJSONObject("jetpack_client_id").getString("value");

                if (jetpackBlogId == null)
                    return null;

                if (currentBlog.getApi_blogid() == null || !currentBlog.getApi_blogid().equals(jetpackBlogId)) {
                    currentBlog.setApi_blogid(jetpackBlogId);
                    currentBlog.save("");
                }

                return jetpackBlogId;
            } else {
                return jetpackBlogId;
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return null;
    }
}
