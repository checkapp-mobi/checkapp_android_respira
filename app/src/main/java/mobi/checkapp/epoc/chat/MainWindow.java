package mobi.checkapp.epoc.chat;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ExpandableListView;
import android.widget.ExpandableListView.ExpandableListContextMenuInfo;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.nullwire.trace.ExceptionHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import mobi.checkapp.epoc.R;
import mobi.checkapp.epoc.chat.IXMPPRosterCallback.Stub;
import mobi.checkapp.epoc.chat.data.ChatProvider;
import mobi.checkapp.epoc.chat.data.ChatProvider.ChatConstants;
import mobi.checkapp.epoc.chat.data.RosterProvider;
import mobi.checkapp.epoc.chat.data.RosterProvider.RosterConstants;
import mobi.checkapp.epoc.chat.data.YaximConfiguration;
import mobi.checkapp.epoc.chat.dialogs.AddRosterItemDialog;
import mobi.checkapp.epoc.chat.dialogs.ChangeStatusDialog;
import mobi.checkapp.epoc.chat.dialogs.FirstStartDialog;
import mobi.checkapp.epoc.chat.dialogs.GroupNameView;
import mobi.checkapp.epoc.chat.preferences.AccountPrefs;
import mobi.checkapp.epoc.chat.preferences.MainPrefs;
import mobi.checkapp.epoc.chat.service.IXMPPRosterService;
import mobi.checkapp.epoc.chat.service.XMPPService;
import mobi.checkapp.epoc.chat.util.ConnectionState;
import mobi.checkapp.epoc.chat.util.PreferenceConstants;
import mobi.checkapp.epoc.chat.util.SimpleCursorTreeAdapter;
import mobi.checkapp.epoc.chat.util.StatusMode;
import mobi.checkapp.epoc.controller.VolleyControler;

public class MainWindow extends AppCompatActivity implements ExpandableListView.OnChildClickListener {

	private final String TAG = this.getClass().getName();

	private YaximConfiguration mConfig;

	private Handler mainHandler = new Handler();

	private Intent xmppServiceIntent;
	private ServiceConnection xmppServiceConnection;
	private XMPPRosterServiceAdapter serviceAdapter;
	private Stub rosterCallback;
	private RosterExpListAdapter rosterListAdapter;
	private TextView mConnectingText;

	private ContentObserver mRosterObserver = new RosterObserver();
	private ContentObserver mChatObserver = new ChatObserver();
	private HashMap<String, Boolean> mGroupsExpanded = new HashMap<String, Boolean>();

	private Toolbar actionBar;
	private ExpandableListView expListView;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		Log.i(TAG, getString(R.string.build_version));
		mConfig = VolleyControler.getConfig(this);
		//set actionBar
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);
		actionBar = (Toolbar) findViewById(R.id.toolbar_chat_activity);
		mConnectingText = (TextView) findViewById(R.id.connecting_view);
		expListView = (ExpandableListView) findViewById(R.id.elListChat);
		expListView.setOnChildClickListener(this);
		setSupportActionBar(actionBar);
		getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		getSupportActionBar().setTitle(getResources().getString(R.string.title_activity_chat));
		//verify other activities
		showFirstStartUpDialogIfPrefsEmpty();
		getContentResolver().registerContentObserver(RosterProvider.CONTENT_URI,
				true, mRosterObserver);
		getContentResolver().registerContentObserver(ChatProvider.CONTENT_URI,
				true, mChatObserver);
		registerXMPPService();
		createUICallback();
		registerListAdapter();
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		getContentResolver().unregisterContentObserver(mRosterObserver);
		getContentResolver().unregisterContentObserver(mChatObserver);
	}

	public int getStatusActionIcon() {
		boolean showOffline = !isConnected() || isConnecting()
					|| getStatusMode() == null;

		if (showOffline) {
			return StatusMode.offline.getDrawableId();
		}

		return getStatusMode().getDrawableId();
	}

	// need this to workaround unwanted OnGroupCollapse/Expand events
	boolean groupClicked = false;
	void handleGroupChange(int groupPosition, boolean isExpanded) {
		if (groupClicked) {
			try {
				String groupName = getGroupName(groupPosition);
				Log.d(TAG, "group status change: " + groupName + " -> " + isExpanded);
				mGroupsExpanded.put(groupName, isExpanded);
			} catch (NullPointerException e) {
				// sometimes, it fails to obtain the cursor. We can ignore it
			}
			groupClicked = false;
		}
	}

	@Override
	protected void onNewIntent(Intent i) {
		setIntent(i);
	}

	@Override
	protected void onPause() {
		super.onPause();
		if (serviceAdapter != null)
			serviceAdapter.unregisterUICallback(rosterCallback);

		VolleyControler.getApp(this).mMTM.unbindDisplayActivity(this);
		unbindXMPPService();
		storeExpandedState();
	}

	@Override
	protected void onResume() {
		super.onResume();
		/**
		if (mConfig.theme.equals(mTheme) == false) {
			// restart
			Intent restartIntent = new Intent(this, MainWindow.class);
			restartIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
			startActivity(restartIntent);
			finish();
		}
		 */
		displayOwnStatus();
		bindXMPPService();

		VolleyControler.getApp(this).mMTM.bindDisplayActivity(this);

		// handle SEND action
		handleSendIntent();
	}

	public void handleSendIntent() {
		Intent intent = getIntent();
		String action = intent.getAction();
		if ((action != null) && (action.equals(Intent.ACTION_SEND))) {
			showToastNotification(R.string.chooseContact);
			setTitle(R.string.chooseContact);
		}
	}

	public void handleJabberIntent() {
		Intent intent = getIntent();
		String action = intent.getAction();
		Uri data = intent.getData();
		if ((action != null) && (action.equals(Intent.ACTION_SENDTO))
				&& data != null && data.getHost().equals("jabber")) {
			String jid = data.getPathSegments().get(0);
			Log.d(TAG, "handleJabberIntent: " + jid);

			List<String[]> contacts = getRosterContacts();
			for (String[] c : contacts) {
				if (jid.equalsIgnoreCase(c[0])) {
					// found it
					startChatActivity(c[0], c[1], null);
					finish();
					return;
				}
			}
			// did not find in roster, try to add
			if (!addToRosterDialog(jid))
				finish();
		}
	}

	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		Log.d(TAG, "onConfigurationChanged");
		expListView.requestFocus();
	}

	private boolean isConnected() {
		return serviceAdapter != null && serviceAdapter.isAuthenticated();
	}
	private boolean isConnecting() {
		return serviceAdapter != null && serviceAdapter.getConnectionState() == ConnectionState.CONNECTING;
	}

	public void updateRoster() {
		loadUnreadCounters();
		rosterListAdapter.requery();
		restoreGroupsExpanded();
	}

	private String getPackedItemRow(long packedPosition, String rowName) {
		int flatPosition = expListView.getFlatListPosition(packedPosition);
		Cursor c = (Cursor)expListView.getItemAtPosition(flatPosition);
		return c.getString(c.getColumnIndex(rowName));
	}

	@Override
	public void onCreateContextMenu(ContextMenu menu, View v,
			ContextMenu.ContextMenuInfo menuInfo) {
		ExpandableListContextMenuInfo info;

		try {
			info = (ExpandableListContextMenuInfo) menuInfo;
		} catch (ClassCastException e) {
			Log.e(TAG, "bad menuinfo: ", e);
			return;
		}

		long packedPosition = info.packedPosition;
		boolean isChild = isChild(packedPosition);

		// get the entry name for the item
		String menuName;
		if (isChild) {
			getMenuInflater().inflate(R.menu.roster_item_contextmenu, menu);
			menuName = String.format("%s (%s)",
				getPackedItemRow(packedPosition, RosterConstants.ALIAS),
				getPackedItemRow(packedPosition, RosterConstants.JID));
		} else {
			menuName = getPackedItemRow(packedPosition, RosterConstants.GROUP);
			if (menuName.equals(""))
				return; // no options for default menu
			getMenuInflater().inflate(R.menu.roster_group_contextmenu, menu);
		}

		menu.setHeaderTitle(getString(R.string.roster_contextmenu_title, menuName));
	}

	void doMarkAllAsRead(final String JID) {
		ContentValues values = new ContentValues();
		values.put(ChatConstants.DELIVERY_STATUS, ChatConstants.DS_SENT_OR_READ);

		getContentResolver().update(ChatProvider.CONTENT_URI, values,
				ChatProvider.ChatConstants.JID + " = ? AND "
						+ ChatConstants.DIRECTION + " = " + ChatConstants.INCOMING + " AND "
						+ ChatConstants.DELIVERY_STATUS + " = " + ChatConstants.DS_NEW,
				new String[]{JID});
	}

	void removeChatHistory(final String JID) {
		getContentResolver().delete(ChatProvider.CONTENT_URI,
				ChatProvider.ChatConstants.JID + " = ?", new String[] { JID });
	}

	void removeChatHistoryDialog(final String JID, final String userName) {
		new AlertDialog.Builder(this)
			.setTitle(R.string.deleteChatHistory_title)
			.setMessage(getString(R.string.deleteChatHistory_text, userName, JID))
			.setPositiveButton(android.R.string.yes,
					new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int which) {
							removeChatHistory(JID);
						}
					})
			.setNegativeButton(android.R.string.no, null)
			.create().show();
	}

	void removeRosterItemDialog(final String JID, final String userName) {
		new AlertDialog.Builder(this)
			.setTitle(R.string.deleteRosterItem_title)
			.setMessage(getString(R.string.deleteRosterItem_text, userName, JID))
			.setPositiveButton(android.R.string.yes,
					new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int which) {
							serviceAdapter.removeRosterItem(JID);
						}
					})
			.setNegativeButton(android.R.string.no, null)
			.create().show();
	}

	boolean addToRosterDialog(String jid) {
		if (serviceAdapter != null && serviceAdapter.isAuthenticated()) {
			new AddRosterItemDialog(this, serviceAdapter, jid).show();
			return true;
		} else {
			showToastNotification(R.string.Global_authenticate_first);
			return false;
		}
	}

	void rosterAddRequestedDialog(final String jid, String message) {
		new AlertDialog.Builder(this)
			.setTitle(R.string.subscriptionRequest_title)
			.setMessage(getString(R.string.subscriptionRequest_text, jid, message))
			.setPositiveButton(android.R.string.yes,
					new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int which) {
							serviceAdapter.sendPresenceRequest(jid, "subscribed");
							addToRosterDialog(jid);
						}
					})
			.setNegativeButton(android.R.string.no, 
					new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int which) {
							serviceAdapter.sendPresenceRequest(jid, "unsubscribed");
						}
					})
			.create().show();
	}

	abstract class EditOk {
		abstract public void ok(String result);
	}

	void editTextDialog(int titleId, CharSequence message, String text,
			final EditOk ok) {
		LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
		View layout = inflater.inflate(R.layout.edittext_dialog,
		                               (ViewGroup) findViewById(R.id.layout_root));

		TextView messageView = (TextView) layout.findViewById(R.id.text);
		messageView.setText(message);
		final EditText input = (EditText) layout.findViewById(R.id.editText);
		input.setTransformationMethod(android.text.method.SingleLineTransformationMethod.getInstance());
		input.setText(text);
		new AlertDialog.Builder(this)
			.setTitle(titleId)
			.setView(layout)
			.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int which) {
							String newName = input.getText().toString();
							if (newName.length() != 0)
								ok.ok(newName);
						}})
			.setNegativeButton(android.R.string.cancel, null)
			.create().show();
	}

	void renameRosterItemDialog(final String JID, final String userName) {
		editTextDialog(R.string.RenameEntry_title,
				getString(R.string.RenameEntry_summ, userName, JID),
				userName, new EditOk() {
					public void ok(String result) {
						serviceAdapter.renameRosterItem(JID, result);
					}
				});
	}

	void renameRosterGroupDialog(final String groupName) {
		editTextDialog(R.string.RenameGroup_title,
				getString(R.string.RenameGroup_summ, groupName),
				groupName, new EditOk() {
					public void ok(String result) {
						serviceAdapter.renameRosterGroup(groupName, result);
					}
				});
	}

	void moveRosterItemToGroupDialog(final String jabberID) {
		LayoutInflater inflater = (LayoutInflater)getSystemService(
			      LAYOUT_INFLATER_SERVICE);
		View group = inflater.inflate(R.layout.moverosterentrytogroupview, null, false);
		final GroupNameView gv = (GroupNameView)group.findViewById(R.id.moverosterentrytogroupview_gv);
		gv.setGroupList(getRosterGroups());
		new AlertDialog.Builder(this)
			.setTitle(R.string.MoveRosterEntryToGroupDialog_title)
			.setView(group)
			.setPositiveButton(android.R.string.ok,
				new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int which) {
						Log.d(TAG, "new group: " + gv.getGroupName());
						serviceAdapter.moveRosterItemToGroup(jabberID,
								gv.getGroupName());
					}
				})
			.setNegativeButton(android.R.string.cancel, null)
			.create().show();
	}

	public boolean onContextItemSelected(MenuItem item) {
		return applyMenuContextChoice(item);
	}

	private boolean applyMenuContextChoice(MenuItem item) {

		ExpandableListContextMenuInfo contextMenuInfo = (ExpandableListContextMenuInfo) item
				.getMenuInfo();
		long packedPosition = contextMenuInfo.packedPosition;

		if (isChild(packedPosition)) {

			String userJid = getPackedItemRow(packedPosition, RosterConstants.JID);
			String userName = getPackedItemRow(packedPosition, RosterConstants.ALIAS);
			Log.d(TAG, "action for contact " + userName + "/" + userJid);

			int itemID = item.getItemId();

			switch (itemID) {
			case R.id.roster_contextmenu_contact_open_chat:
				startChatActivity(userJid, userName, null);
				return true;

			case R.id.roster_contextmenu_contact_mark_all_as_read:
				doMarkAllAsRead(userJid);
				return true;

			case R.id.roster_contextmenu_contact_delmsg:
				removeChatHistoryDialog(userJid, userName);
				return true;

			case R.id.roster_contextmenu_contact_delete:
				if (!isConnected()) { showToastNotification(R.string.Global_authenticate_first); return true; }
				removeRosterItemDialog(userJid, userName);
				return true;

			case R.id.roster_contextmenu_contact_rename:
				if (!isConnected()) { showToastNotification(R.string.Global_authenticate_first); return true; }
				renameRosterItemDialog(userJid, userName);
				return true;

			case R.id.roster_contextmenu_contact_request_auth:
				if (!isConnected()) { showToastNotification(R.string.Global_authenticate_first); return true; }
				serviceAdapter.sendPresenceRequest(userJid, "subscribe");
				return true;

			case R.id.roster_contextmenu_contact_change_group:
				if (!isConnected()) { showToastNotification(R.string.Global_authenticate_first); return true; }
				moveRosterItemToGroupDialog(userJid);
				return true;
			}
		} else {

			int itemID = item.getItemId();
			String seletedGroup = getPackedItemRow(packedPosition, RosterConstants.GROUP);
			Log.d(TAG, "action for group " + seletedGroup);

			switch (itemID) {
			case R.id.roster_contextmenu_group_rename:
				if (!isConnected()) { showToastNotification(R.string.Global_authenticate_first); return true; }
				renameRosterGroupDialog(seletedGroup);
				return true;

			}
		}
		return false;
	}

	private boolean isChild(long packedPosition) {
		int type = ExpandableListView.getPackedPositionType(packedPosition);
		return (type == ExpandableListView.PACKED_POSITION_TYPE_CHILD);
	}

	private void startChatActivity(String user, String userName, String message) {
		Intent chatIntent = new Intent(this,
				mobi.checkapp.epoc.chat.chat.ChatWindow.class);
		Uri userNameUri = Uri.parse(user);
		chatIntent.setData(userNameUri);
		chatIntent.putExtra(mobi.checkapp.epoc.chat.chat.ChatWindow.INTENT_EXTRA_USERNAME, userName);
		if (message != null) {
			chatIntent.putExtra(mobi.checkapp.epoc.chat.chat.ChatWindow.INTENT_EXTRA_MESSAGE, message);
		}
		startActivity(chatIntent);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.roster_options, menu);
		return super.onCreateOptionsMenu(menu);
	}

	void setMenuItem(Menu menu, int itemId, int iconId, CharSequence title) {
		MenuItem item = menu.findItem(itemId);
		if (item == null)
			return;
		item.setIcon(iconId);
		item.setTitle(title);
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		setMenuItem(menu, R.id.menu_connect, getConnectDisconnectIcon(),
				getConnectDisconnectText());
		setMenuItem(menu, R.id.menu_show_hide, getShowHideMenuIcon(),
				getShowHideMenuText());
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		return applyMainMenuChoice(item);
	}

	private int getShowHideMenuIcon() {
		TypedValue tv = new TypedValue();
		if (mConfig.showOffline) {
			getTheme().resolveAttribute(R.attr.OnlineFriends, tv, true);
			return tv.resourceId;
		}
		getTheme().resolveAttribute(R.attr.AllFriends, tv, true);
		return tv.resourceId;
	}

	private String getShowHideMenuText() {
		return mConfig.showOffline ? getString(R.string.Menu_HideOff)
				: getString(R.string.Menu_ShowOff);
	}

	public StatusMode getStatusMode() {
		return StatusMode.fromString(mConfig.statusMode);
	}

	public void setAndSaveStatus(StatusMode statusMode, String message) {
		SharedPreferences.Editor prefedit = PreferenceManager
				.getDefaultSharedPreferences(this).edit();
		// do not save "offline" to prefs, or else!
		if (statusMode != StatusMode.offline)
			prefedit.putString(PreferenceConstants.STATUS_MODE, statusMode.name());
		if (!message.equals(mConfig.statusMessage)) {
			List<String> smh = new ArrayList<String>(java.util.Arrays.asList(mConfig.statusMessageHistory));
			if (!smh.contains(message))
				smh.add(message);
			String smh_joined = android.text.TextUtils.join("\036", smh);
			prefedit.putString(PreferenceConstants.STATUS_MESSAGE_HISTORY, smh_joined);
		}
		prefedit.putString(PreferenceConstants.STATUS_MESSAGE, message);
		prefedit.commit();

		displayOwnStatus();

		// check if we are connected and want to go offline
		boolean needToDisconnect = (statusMode == StatusMode.offline) && isConnected();
		// check if we want to reconnect
		boolean needToConnect = (statusMode != StatusMode.offline) &&
				serviceAdapter.getConnectionState() == ConnectionState.OFFLINE;

		if (needToConnect || needToDisconnect)
			toggleConnection();
		else if (isConnected())
			serviceAdapter.setStatusFromConfig();
	}

	private void displayOwnStatus() {
		// This and many other things like it should be done with observer
		if(actionBar != null) {
			//actionBar.setIcon(getStatusActionIcon());

			if (mConfig.statusMessage.equals("")) {
				//actionBar.setSubtitle(null);
			} else {
				//actionBar.setSubtitle(mConfig.statusMessage);
			}
		}
	}

	private void aboutDialog() {
		LayoutInflater inflater = (LayoutInflater)getSystemService(
			      LAYOUT_INFLATER_SERVICE);
		View about = inflater.inflate(R.layout.aboutview, null, false);
		String versionTitle = getString(R.string.AboutDialog_title);
		try {
			PackageInfo pi = getPackageManager()
						.getPackageInfo(getPackageName(), 0);
			versionTitle += " v" + pi.versionName;
		} catch (NameNotFoundException e) {
		}

		// fix translator-credits: hide if unset, format otherwise
		TextView tcv = (TextView)about.findViewById(R.id.translator_credits);
		if (tcv.getText().equals("translator-credits"))
			tcv.setVisibility(View.GONE);

		new AlertDialog.Builder(this)
			.setTitle(versionTitle)
			.setIcon(android.R.drawable.ic_dialog_info)
			.setView(about)
			.setPositiveButton(android.R.string.ok, null)
			.setNeutralButton(R.string.AboutDialog_Vote, new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int item) {
					Intent market = new Intent(Intent.ACTION_VIEW,
						Uri.parse("market://details?id=" + getPackageName()));
					market.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY | Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
					try {
						startActivity(market);
					} catch (Exception e) {
						// do not crash
						Log.e(TAG, "could not go to market: " + e);
					}
				}
			})
			.create().show();
	}

	private boolean applyMainMenuChoice(MenuItem item) {

		int itemID = item.getItemId();

		switch (itemID) {
		case R.id.menu_connect:
			toggleConnection();
			return true;

		case R.id.menu_add_friend:
			addToRosterDialog(null);
			return true;

		case R.id.menu_show_hide:
			setOfflinceContactsVisibility(!mConfig.showOffline);
			updateRoster();
			return true;

		case android.R.id.home:
			Log.d(TAG, "Back button clicked");
			super.onBackPressed();
			finish();
			return true;
		case R.id.menu_status:
			new ChangeStatusDialog(this, StatusMode.fromString(mConfig.statusMode),
					mConfig.statusMessage, mConfig.statusMessageHistory).show();
			return true;
		case R.id.menu_exit:
			PreferenceManager.getDefaultSharedPreferences(this).edit().
				putBoolean(PreferenceConstants.CONN_STARTUP, false).commit();
			stopService(xmppServiceIntent);
			finish();
			return true;

		case R.id.menu_settings:
			startActivity(new Intent(this, MainPrefs.class));
			return true;

		case R.id.menu_about:
			//aboutDialog();
			return true;

		}
		return false;
	}

	/** Sets if all contacts are shown in the roster or online contacts only. */
	//@TargetApi(Build.VERSION_CODES.HONEYCOMB) // required for Sherlock's invalidateOptionsMenu */
	private void setOfflinceContactsVisibility(boolean showOffline) {
		PreferenceManager.getDefaultSharedPreferences(this).edit().
			putBoolean(PreferenceConstants.SHOW_OFFLINE, showOffline).commit();
		invalidateOptionsMenu();
	}

	@Override
	public boolean onChildClick(ExpandableListView parent, View v,
			int groupPosition, int childPosition, long id) {

		long packedPosition = ExpandableListView.getPackedPositionForChild(groupPosition, childPosition);
		Cursor c = (Cursor)expListView.getItemAtPosition(expListView.getFlatListPosition(packedPosition));
		String userJid = c.getString(c.getColumnIndexOrThrow(RosterConstants.JID));
		String userName = c.getString(c.getColumnIndexOrThrow(RosterConstants.ALIAS));
		Intent i = getIntent();
		if (i.getAction() != null && i.getAction().equals(Intent.ACTION_SEND)) {
			// delegate ACTION_SEND to child window and close self
			startChatActivity(userJid, userName, i.getStringExtra(Intent.EXTRA_TEXT));
			finish();
		} else {
			StatusMode s = StatusMode.values()[c.getInt(c.getColumnIndexOrThrow(RosterConstants.STATUS_MODE))];
			if (s == StatusMode.subscribe)
				rosterAddRequestedDialog(userJid,
					c.getString(c.getColumnIndexOrThrow(RosterConstants.STATUS_MESSAGE)));
			else
				startChatActivity(userJid, userName, null);
		}

		return true;
	}

	private void updateConnectionState(ConnectionState cs) {
		Log.d(TAG, "updateConnectionState: " + cs);
		displayOwnStatus();
		boolean spinTheSpinner = false;
		switch (cs) {
		case CONNECTING:
		case DISCONNECTING:
			spinTheSpinner = true;
		case DISCONNECTED:
		case RECONNECT_NETWORK:
		case RECONNECT_DELAYED:
		case OFFLINE:
			if (cs == ConnectionState.OFFLINE) // override with "Offline" string, no error message
				mConnectingText.setText(R.string.conn_offline);
			else
				mConnectingText.setText(serviceAdapter.getConnectionStateString());
			mConnectingText.setVisibility(View.VISIBLE);
			setSupportProgressBarIndeterminateVisibility(spinTheSpinner);
			break;
		case ONLINE:
			mConnectingText.setVisibility(View.GONE);
			setSupportProgressBarIndeterminateVisibility(false);
		}
	}
	
	public void startConnection(boolean create_account) {
		xmppServiceIntent.putExtra("create_account", create_account);
		startService(xmppServiceIntent);
	}

	// this function changes the prefs to keep the connection
	// according to the requested state
	private void toggleConnection() {
		if (!mConfig.jid_configured) {
			startActivity(new Intent(this, AccountPrefs.class));
			return;
		}
		boolean oldState = isConnected() || isConnecting();

		PreferenceManager.getDefaultSharedPreferences(this).edit().
			putBoolean(PreferenceConstants.CONN_STARTUP, !oldState).commit();
		if (oldState) {
			serviceAdapter.disconnect();
			stopService(xmppServiceIntent);
		} else
			startConnection(false);
	}

	private int getConnectDisconnectIcon() {
		if (isConnected() || isConnecting()) {
			return R.drawable.ic_menu_unplug;
		}
		return R.drawable.ic_menu_plug;
	}

	private String getConnectDisconnectText() {
		if (isConnected() || isConnecting()) {
			return getString(R.string.Menu_disconnect);
		}
		return getString(R.string.Menu_connect);
	}

	private void registerXMPPService() {
		Log.i(TAG, "called startXMPPService()");
		xmppServiceIntent = new Intent(this, XMPPService.class);
		xmppServiceIntent.setAction("mobi.checkapp.epoc.chat.XMPPSERVICE");

		xmppServiceConnection = new ServiceConnection() {
			//@TargetApi(Build.VERSION_CODES.HONEYCOMB) // required for Sherlock's invalidateOptionsMenu */
			public void onServiceConnected(ComponentName name, IBinder service) {
				Log.i(TAG, "called onServiceConnected()");
				serviceAdapter = new XMPPRosterServiceAdapter(
						IXMPPRosterService.Stub.asInterface(service));
				serviceAdapter.registerUICallback(rosterCallback);
				Log.i(TAG, "getConnectionState(): "
						+ serviceAdapter.getConnectionState());
				invalidateOptionsMenu();	// to load the action bar contents on time for access to icons/progressbar
				ConnectionState cs = serviceAdapter.getConnectionState();
				updateConnectionState(cs);
				updateRoster();

				// when returning from prefs to main activity, apply new config
				if (mConfig.reconnect_required && cs == ConnectionState.ONLINE) {
					// login config changed, force reconnection
					serviceAdapter.disconnect();
					serviceAdapter.connect();
				} else if (mConfig.presence_required && isConnected())
					serviceAdapter.setStatusFromConfig();

				// handle server-related intents after connecting to the backend
				handleJabberIntent();
			}

			public void onServiceDisconnected(ComponentName name) {
				Log.i(TAG, "called onServiceDisconnected()");
			}
		};
	}

	private void unbindXMPPService() {
		try {
			unbindService(xmppServiceConnection);
		} catch (IllegalArgumentException e) {
			Log.e(TAG, "Service wasn't bound!");
		}
	}

	private void bindXMPPService() {
		bindService(xmppServiceIntent, xmppServiceConnection, BIND_AUTO_CREATE);
	}

	private void registerListAdapter() {

		rosterListAdapter = new RosterExpListAdapter(this);
		expListView.setAdapter(rosterListAdapter);
	}

	private void createUICallback() {
		rosterCallback = new IXMPPRosterCallback.Stub() {
			@Override
			public void connectionStateChanged(final int connectionstate)
						throws RemoteException {
				mainHandler.post(new Runnable() {
					//@TargetApi(Build.VERSION_CODES.HONEYCOMB) // required for Sherlock's invalidateOptionsMenu */
					public void run() {
						ConnectionState cs = ConnectionState.values()[connectionstate];
						//Log.d(TAG, "connectionStatusChanged: " + cs);
						updateConnectionState(cs);
						invalidateOptionsMenu();
					}
				});
			}
		};
	}

	// store mGroupsExpanded into prefs (this is a hack, but SQLite /
	// content providers suck wrt. virtual groups)
	public void storeExpandedState() {
		SharedPreferences.Editor prefedit = PreferenceManager
				.getDefaultSharedPreferences(this).edit();
		for (HashMap.Entry<String, Boolean> item : mGroupsExpanded.entrySet()) {
			prefedit.putBoolean("expanded_" + item.getKey(), item.getValue());
		}
		prefedit.commit();
	}

	// get the name of a roster group from the cursor
	public String getGroupName(int groupId) {
		return getPackedItemRow(ExpandableListView.getPackedPositionForGroup(groupId),
				RosterConstants.GROUP);
	}

	public void restoreGroupsExpanded() {
		SharedPreferences prefs = PreferenceManager
				.getDefaultSharedPreferences(this);
		for (int count = 0; count < expListView.getExpandableListAdapter().getGroupCount(); count++) {
			String name = getGroupName(count);
			if (!mGroupsExpanded.containsKey(name))
				mGroupsExpanded.put(name, prefs.getBoolean("expanded_" + name, true));
			Log.d(TAG, "restoreGroupsExpanded: " + name + ": " + mGroupsExpanded.get(name));
			if (mGroupsExpanded.get(name))
				expListView.expandGroup(count);
			else
				expListView.collapseGroup(count);
		}
	}

	private void showFirstStartUpDialogIfPrefsEmpty() {
		Log.i(TAG, "showFirstStartUpDialogIfPrefsEmpty, JID: "
						+ mConfig.jabberID);
		if (mConfig.jabberID.length() < 3) {
			// load preference defaults
			PreferenceManager.setDefaultValues(this, R.layout.mainprefs, false);
			PreferenceManager.setDefaultValues(this, R.layout.accountprefs, false);

			// prevent a start-up with empty JID
			SharedPreferences prefs = PreferenceManager
					.getDefaultSharedPreferences(this);
			prefs.edit().putBoolean(PreferenceConstants.CONN_STARTUP, false).commit();

			// show welcome dialog
			new FirstStartDialog(this, serviceAdapter).show();
		}
	}

	public static Intent createIntent(Context context) {
		Intent i = new Intent(context, MainWindow.class);
		i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
		return i;
	}

	protected void showToastNotification(int message) {
		Toast tmptoast = Toast.makeText(this, message, Toast.LENGTH_SHORT);
		tmptoast.show();
	}

	private void registerCrashReporter() {
		if (mConfig.reportCrash) {
			ExceptionHandler.register(this, "http://www.checkapp.mobi/crash/");
		}
	}

	private static final String OFFLINE_EXCLUSION =
			RosterConstants.STATUS_MODE + " != " + StatusMode.offline.ordinal();
	private static final String countAvailableMembers =
			"SELECT COUNT() FROM " + RosterProvider.TABLE_ROSTER + " inner_query" +
					" WHERE inner_query." + RosterConstants.GROUP + " = " +
					RosterProvider.QUERY_ALIAS + "." + RosterConstants.GROUP +
					" AND inner_query." + OFFLINE_EXCLUSION;
	private static final String countMembers =
			"SELECT COUNT() FROM " + RosterProvider.TABLE_ROSTER + " inner_query" +
					" WHERE inner_query." + RosterConstants.GROUP + " = " +
					RosterProvider.QUERY_ALIAS + "." + RosterConstants.GROUP;
	private static final String[] GROUPS_QUERY = new String[] {
		RosterConstants._ID,
		RosterConstants.GROUP,
	};
	private static final String[] GROUPS_QUERY_COUNTED = new String[] {
		RosterConstants._ID,
		RosterConstants.GROUP,
		"(" + countAvailableMembers + ") || '/' || (" + countMembers + ") AS members"
	};

	final String countAvailableMembersTotals =
			"SELECT COUNT() FROM " + RosterProvider.TABLE_ROSTER + " inner_query" +
					" WHERE inner_query." + OFFLINE_EXCLUSION;
	final String countMembersTotals =
			"SELECT COUNT() FROM " + RosterProvider.TABLE_ROSTER;
	final String[] GROUPS_QUERY_CONTACTS_DISABLED = new String[] {
			RosterConstants._ID,
			"'' AS " + RosterConstants.GROUP,
			"(" + countAvailableMembersTotals + ") || '/' || (" + countMembersTotals + ") AS members"
	};

	private static final String[] GROUPS_FROM = new String[] {
		RosterConstants.GROUP,
		"members"
	};
	private static final int[] GROUPS_TO = new int[] {
		R.id.groupname,
		R.id.members
	};
	private static final String[] ROSTER_QUERY = new String[] {
		RosterConstants._ID,
		RosterConstants.JID,
		RosterConstants.ALIAS,
		RosterConstants.STATUS_MODE,
		RosterConstants.STATUS_MESSAGE,
	};

	public List<String> getRosterGroups() {
		// we want all, online and offline
		List<String> list = new ArrayList<String>();
		Cursor cursor = getContentResolver().query(RosterProvider.GROUPS_URI, GROUPS_QUERY,
					null, null, RosterConstants.GROUP);
		int idx = cursor.getColumnIndex(RosterConstants.GROUP);
		cursor.moveToFirst();
		while (!cursor.isAfterLast()) {
			list.add(cursor.getString(idx));
			cursor.moveToNext();
		}
		cursor.close();
		return list;
	}

	public List<String[]> getRosterContacts() {
		// we want all, online and offline
		List<String[]> list = new ArrayList<String[]>();
		Cursor cursor = getContentResolver().query(RosterProvider.CONTENT_URI, ROSTER_QUERY,
					null, null, RosterConstants.ALIAS);
		int JIDIdx = cursor.getColumnIndex(RosterConstants.JID);
		int aliasIdx = cursor.getColumnIndex(RosterConstants.ALIAS);
		cursor.moveToFirst();
		while (!cursor.isAfterLast()) {
			String jid = cursor.getString(JIDIdx);
			String alias = cursor.getString(aliasIdx);
			if ((alias == null) || (alias.length() == 0)) alias = jid;
			list.add(new String[] { jid, alias });
			cursor.moveToNext();
		}
		cursor.close();
		return list;
	}

	public class RosterExpListAdapter extends SimpleCursorTreeAdapter {

		public RosterExpListAdapter(Context context) {
			super(context, /* cursor = */ null, 
					R.layout.maingroup_row, GROUPS_FROM, GROUPS_TO,
					R.layout.mainchild_row,
					new String[] {
						RosterConstants.ALIAS,
						RosterConstants.STATUS_MESSAGE,
						RosterConstants.STATUS_MODE
					},
					new int[] {
						R.id.roster_screenname,
						R.id.roster_statusmsg,
						R.id.roster_icon
					});
		}

		public void requery() {
			String selectWhere = null;
			if (!mConfig.showOffline)
				selectWhere = OFFLINE_EXCLUSION;

			String[] query = GROUPS_QUERY_COUNTED;
			if(!mConfig.enableGroups) {
				query = GROUPS_QUERY_CONTACTS_DISABLED;
			}
			Cursor cursor = getContentResolver().query(RosterProvider.GROUPS_URI,
					query, selectWhere, null, RosterConstants.GROUP);
			Cursor oldCursor = getCursor();
			changeCursor(cursor);
			if (oldCursor != null)
				stopManagingCursor(oldCursor);
		}

		@Override
		protected Cursor getChildrenCursor(Cursor groupCursor) {
			// Given the group, we return a cursor for all the children within that group
			String selectWhere;
			int idx = groupCursor.getColumnIndex(RosterConstants.GROUP);
			String groupname = groupCursor.getString(idx);
			String[] args = null;

			if(!mConfig.enableGroups) {
				selectWhere = mConfig.showOffline ? "" : OFFLINE_EXCLUSION;
			} else {
				selectWhere = mConfig.showOffline ? "" : OFFLINE_EXCLUSION + " AND ";
				selectWhere += RosterConstants.GROUP + " = ?";
				args = new String[] { groupname };
			}
			return getContentResolver().query(RosterProvider.CONTENT_URI, ROSTER_QUERY,
				selectWhere, args, null);
		}

		@Override
		protected void bindGroupView(View view, Context context, Cursor cursor, boolean isExpanded) {
			super.bindGroupView(view, context, cursor, isExpanded);
			if (cursor.getString(cursor.getColumnIndexOrThrow(RosterConstants.GROUP)).length() == 0) {
				TextView groupname = (TextView)view.findViewById(R.id.groupname);
				groupname.setText(mConfig.enableGroups ? R.string.default_group : R.string.all_contacts_group);
			}
			ImageView imageListExerciseHeader = (ImageView) view.findViewById(R.id.imageListExerciseHeader);
			Drawable icon;
			int iconId;
			if (isExpanded) {
				iconId = R.drawable.ic_expand_less_white_24dp;
			} else {
				iconId = R.drawable.ic_expand_more_white_24dp;
			}
			icon = view.getResources().getDrawable(iconId);
			icon.setColorFilter(view.getResources().getColor(R.color.colorPrimaryDark), PorterDuff.Mode.SRC_ATOP);
			imageListExerciseHeader.setImageDrawable(icon);
		}

		@Override
		protected void bindChildView(View view, Context context, Cursor cursor, boolean isLastChild) {
			super.bindChildView(view, context, cursor, isLastChild);
			TextView statusmsg = (TextView)view.findViewById(R.id.roster_statusmsg);
			boolean hasStatus = statusmsg.getText() != null && statusmsg.getText().length() > 0;
			statusmsg.setVisibility(hasStatus ? View.VISIBLE : View.GONE);

			String jid = cursor.getString(cursor.getColumnIndex(RosterConstants.JID));
			TextView unreadmsg = (TextView)view.findViewById(R.id.roster_unreadmsg_cnt);
			Integer count = mUnreadCounters.get(jid);
			if (count == null)
				count = 0;
			unreadmsg.setText(count.toString());
			unreadmsg.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
			unreadmsg.bringToFront();
		}

		 protected void setViewImage(ImageView v, String value) {
			int presenceMode = Integer.parseInt(value);
			v.setImageResource(getIconForPresenceMode(presenceMode));
		 }

		private int getIconForPresenceMode(int presenceMode) {
			if (!isConnected()) // override icon if we are offline
				presenceMode = 0;
			return StatusMode.values()[presenceMode].getDrawableId();
		}
	}

	private class RosterObserver extends ContentObserver {
		public RosterObserver() {
			super(mainHandler);
		}
		public void onChange(boolean selfChange) {
			Log.d(TAG, "RosterObserver.onChange: " + selfChange);
			// work around race condition in ExpandableListView, which collapses
			// groups rand-f**king-omly
			if (expListView != null && expListView.getExpandableListAdapter() != null)
				mainHandler.postDelayed(new Runnable() {
					public void run() {
						restoreGroupsExpanded();
					}}, 100);
		}
	}

	private HashMap<String, Integer> mUnreadCounters = new HashMap<String, Integer>();
	private void loadUnreadCounters() {
		final String[] PROJECTION = new String[] { ChatConstants.JID, "count(*)" };
		final String SELECTION = ChatConstants.DIRECTION + " = " + ChatConstants.INCOMING + " AND " +
			ChatConstants.DELIVERY_STATUS + " = " + ChatConstants.DS_NEW +
			") GROUP BY (" + ChatConstants.JID; // hack!

		Cursor c = getContentResolver().query(ChatProvider.CONTENT_URI,
				PROJECTION, SELECTION, null, null);
		mUnreadCounters.clear();
		if(c!=null){
			while (c.moveToNext())
				mUnreadCounters.put(c.getString(0), c.getInt(1));
			c.close();
		}
	}

	private class ChatObserver extends ContentObserver {
		public ChatObserver() {
			super(mainHandler);
		}
		public void onChange(boolean selfChange) {
			updateRoster();
		}
	}

	@Override
	public void onBackPressed() {
		super.onBackPressed();
		finish();
	}
}
