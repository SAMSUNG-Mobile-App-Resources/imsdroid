package org.doubango.imsdroid.Screens;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;

import org.doubango.imsdroid.IMSDroid;
import org.doubango.imsdroid.Main;
import org.doubango.imsdroid.R;
import org.doubango.imsdroid.ServiceManager;
import org.doubango.imsdroid.Events.EventArgs;
import org.doubango.imsdroid.Events.InviteEventArgs;
import org.doubango.imsdroid.Media.MediaType;
import org.doubango.imsdroid.Services.IConfigurationService;
import org.doubango.imsdroid.Services.IScreenService;
import org.doubango.imsdroid.Services.Impl.SipService;
import org.doubango.imsdroid.Sip.MyAVSession;
import org.doubango.imsdroid.Sip.MySipStack;
import org.doubango.imsdroid.Sip.MyInviteSession.InviteState;
import org.doubango.imsdroid.Utils.ConfigurationUtils;
import org.doubango.imsdroid.Utils.StringUtils;
import org.doubango.imsdroid.Utils.UriUtils;
import org.doubango.imsdroid.Utils.ConfigurationUtils.ConfigurationEntry;

import android.app.KeyguardManager;
import android.app.KeyguardManager.KeyguardLock;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.RelativeLayout;
import android.widget.TextView;

public class ScreenAV extends BaseScreen{
	private static final String TAG = ScreenAV.class.getCanonicalName();
	private static final SimpleDateFormat durationTimerFormat = new SimpleDateFormat("mm:ss");
	private static int countBlankPacket=0;
	
	@SuppressWarnings("unused")
	private final IConfigurationService mConfigurationService;
	private final IScreenService mScreenService;
	
	private ViewType mCurrentView;
	private LayoutInflater mInflater;
	private RelativeLayout mMainLayout;
	private BroadcastReceiver mSipBroadCastRecv;
	
	private final Timer mTimerInCall;
	private final Timer mTimerSuicide;
	private final Timer mTimerBlankPacket;
	private MyAVSession mAVSession;
	
	private TextView mTvInfo;
	private TextView mTvDuration;
	
	private MyProxSensor mProxSensor;
	private KeyguardLock mKeyguardLock;
	
	private static final int SELECT_CONTENT = 1;
	
	private final static int MENU_PICKUP = 0;
	private final static int MENU_HANGUP= 1;
	private final static int MENU_HOLD_RESUME = 2;
	private final static int MENU_SEND_STOP_VIDEO = 3;
	private final static int MENU_SHARE_CONTENT = 4;
	private final static int MENU_SPEAKER = 5;
	
	private static enum ViewType{
		ViewNone,
		ViewTrying,
		ViewInCall,
		ViewProxSensor,
		ViewTermwait
	}
	
	public ScreenAV() {
		super(SCREEN_TYPE.AV_T, TAG);
		
		mCurrentView = ViewType.ViewNone;
		
		mTimerInCall = new Timer();
		mTimerSuicide = new Timer();
		mTimerBlankPacket = new Timer();
		
		mConfigurationService = ServiceManager.getConfigurationService();
		mScreenService = ServiceManager.getScreenService();
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.screen_av);
		
		super.mId = getIntent().getStringExtra("id");
		if(StringUtils.isNullOrEmpty(super.mId)){
			Log.e(TAG, "Invalid audio/video session");
			finish(); 
			mScreenService.show(ScreenHome.class);
			return;
		}
		mAVSession = MyAVSession.getSession(StringUtils.parseLong(super.mId, -1));
		if(mAVSession == null){
			Log.e(TAG, "Cannot find audio/video session");
			finish(); 
			mScreenService.show(ScreenHome.class);
			return;
		}
		else{
			mAVSession.incRef();
		}
		
		mInflater = LayoutInflater.from(this);
		
		mSipBroadCastRecv = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				handleSipEvent(intent);
			}
		};
		IntentFilter intentFilter = new IntentFilter();
		intentFilter.addAction(SipService.ACTION_INVITE_EVENT);
	    registerReceiver(mSipBroadCastRecv, intentFilter);
		
		mMainLayout = (RelativeLayout)findViewById(R.id.screen_av_relativeLayout);
        loadView();
        
        setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);
	}
	
	@Override
	protected void onStart() {
		super.onStart();
		
		final KeyguardManager keyguardManager = IMSDroid.getKeyguardManager();
		if(keyguardManager != null){
			if(mKeyguardLock == null){
				mKeyguardLock = keyguardManager.newKeyguardLock(ScreenAV.TAG);
			}
			if(keyguardManager.inKeyguardRestrictedInputMode()){
				mKeyguardLock.disableKeyguard();
			}
		}
		
		if(mProxSensor == null){
			mProxSensor = new MyProxSensor(this);
		}
	}
	
	@Override
	protected void onPause() {
		super.onPause();
		
		if(mProxSensor != null){
			mProxSensor.stop();
		}
	}

	@Override
	protected void onResume() {
		super.onResume();
		
		if(mProxSensor != null){
			mProxSensor.start();
		}
	}

	@Override
	protected void onStop() {
		super.onStop();
		
		if(mKeyguardLock != null){
			mKeyguardLock.reenableKeyguard();
		}
	}
	
	@Override
	protected void onDestroy() {
       if(mSipBroadCastRecv != null){
    	   unregisterReceiver(mSipBroadCastRecv);
    	   mSipBroadCastRecv = null;
       }
       
       mTimerInCall.cancel();
       mTimerSuicide.cancel();
       cancelBlankPacket();
       
       if(mAVSession != null){
    	   mAVSession.setContext(null);
    	   mAVSession.decRef();
       } 
       super.onDestroy();
	}
	
	@Override
	public boolean hasMenu(){
		return true;
	}
	
	@Override
	public boolean createOptionsMenu(Menu menu){
		if(mAVSession == null){
			return false;
		}
		
		MenuItem itemPickUp = menu.add(0, ScreenAV.MENU_PICKUP, 0, "Answer");
		MenuItem itemHangUp = menu.add(0, ScreenAV.MENU_HANGUP, 0, "Hang-up");
		MenuItem itemHoldResume = menu.add(0, ScreenAV.MENU_HOLD_RESUME, 0, "Hold");
		MenuItem itemSendStopVideo = menu.add(1, ScreenAV.MENU_SEND_STOP_VIDEO, 0, "Send Video");
		MenuItem itemShareContent = menu.add(1, ScreenAV.MENU_SHARE_CONTENT, 0, "Share Content");
		MenuItem itemSpeaker = menu.add(1, ScreenAV.MENU_SPEAKER, 0, "Speaker ON");
		
		switch(mAVSession.getState()){
			case INCOMING:
			{
				itemPickUp.setEnabled(true);
				itemHangUp.setEnabled(true);
				itemHoldResume.setEnabled(false);
				itemSpeaker.setEnabled(false);
				itemSendStopVideo.setEnabled(false);
				itemShareContent.setEnabled(false);
				break;
			}
			
			case INPROGRESS:
			{
				itemPickUp.setEnabled(false);
				itemHangUp.setEnabled(true);
				itemHoldResume.setEnabled(false);
				itemSpeaker.setEnabled(false);
				itemSendStopVideo.setEnabled(false);
				itemShareContent.setEnabled(false);
				break;
			}
			
			case INCALL:
			{
				itemHangUp.setEnabled(true);
				itemHoldResume.setEnabled(true);
				itemSpeaker.setEnabled(true);
				itemSpeaker.setTitle(IMSDroid.getAudioManager().isSpeakerphoneOn() ? "Speaker OFF" : "Speaker ON");
				
				if((mAVSession.getMediaType() == MediaType.AudioVideo || mAVSession.getMediaType() == MediaType.Video)){
					itemSendStopVideo.setTitle(mAVSession.isSendingVideo()? "Stop Video" : "Send Video");
					itemSendStopVideo.setEnabled(true);
					// Replace Answer by camera switcher
					itemPickUp.setEnabled(true);
					itemPickUp.setTitle("Switch camera")/*.setIcon(R.drawable.refresh_48)*/;
				}
				else{
					itemPickUp.setEnabled(false);
					itemSendStopVideo.setEnabled(false);
				}
				itemShareContent.setEnabled(true);
				itemHoldResume.setTitle(mAVSession.isLocalHeld()? "Resume" : "Hold");
			
				break;
			}
				
			case TERMINATED:
			case TERMINATING:
			{
				itemPickUp.setEnabled(false);
				itemHangUp.setEnabled(false);
				itemHoldResume.setEnabled(false);
				itemSpeaker.setEnabled(false);
				itemSendStopVideo.setEnabled(false);
				itemShareContent.setEnabled(false);
				break;
			}
		}
		
		return true;
	}
	
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if(mAVSession == null){
			return false;
		}
		switch(item.getItemId()){
			case ScreenAV.MENU_PICKUP:
			{
				if (mAVSession.getState() == InviteState.INCALL) {
					Log.d(TAG, "Toggle Camera");
					mAVSession.toggleCamera();
				} else {
					acceptCall();
				}
				break;
			}
				
			case ScreenAV.MENU_HANGUP:
			{
				if(mTvInfo != null){
					mTvInfo.setText("Ending the call...");
				}
				hangUpCall();
				break;
			}
				
			case ScreenAV.MENU_HOLD_RESUME:
			{
				if(mAVSession.isLocalHeld()){
					mAVSession.resumeCall();
				}
				else{
					mAVSession.holdCall();
				}
				break;
			}
				
			case ScreenAV.MENU_SEND_STOP_VIDEO:
			{
				 startStopVideo(!mAVSession.isSendingVideo());
				 break;
			}
				
			case ScreenAV.MENU_SHARE_CONTENT:
			{
				 Intent intent = new Intent();
				 intent.setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).setAction(Intent.ACTION_GET_CONTENT);
				 startActivityForResult(Intent.createChooser(intent, "Select content"), ScreenAV.SELECT_CONTENT);
				break;
			}
			
			case ScreenAV.MENU_SPEAKER:
			{
				mAVSession.toggleSpeakerphone();
				break;
			}
		}
		return true;
	}
	
	public static boolean receiveCall(MyAVSession avSession){
		ServiceManager.getScreenService().bringToFront(Main.ACTION_SHOW_AVSCREEN,
				new String[] {"session-id", Long.toString(avSession.getId())}
		);
		return true;
	}
	
	public static boolean makeCall(String remoteUri, MediaType mediaType){		
		String validUri = UriUtils.makeValidSipUri(remoteUri);
		if(validUri == null){
			Log.e(TAG, "failed to normalize sip uri '" + remoteUri + "'");
			return false;
		}
		else{
			remoteUri = validUri;
			if(remoteUri.startsWith("tel:")){
				// E.164 number => use ENUM protocol
				final MySipStack sipStack = ServiceManager.getSipService().getSipStack();
				if(sipStack != null){
					String phoneNumber = UriUtils.getValidPhoneNumber(remoteUri);
					if(phoneNumber != null){
						String enumDomain = ServiceManager.getConfigurationService().getString(
								ConfigurationEntry.GENERAL_ENUM_DOMAIN, ConfigurationUtils.DEFAULT_GENERAL_ENUM_DOMAIN);
						String sipUri = sipStack.dnsENUM("E2U+SIP", phoneNumber, enumDomain);
						if(sipUri != null){
							remoteUri = sipUri;
						}
					}
				}
			}
		}
		
		MyAVSession avSession = MyAVSession.createOutgoingSession(ServiceManager.getSipService().getSipStack(), mediaType);
		avSession.setRemotePartyUri(remoteUri); // HACK
		ServiceManager.getScreenService().show(ScreenAV.class, Long.toString(avSession.getId()));	
		
		// Hold the active call
		final MyAVSession activeCall = MyAVSession.getFirstActiveCallAndNot(avSession.getId());
		if(activeCall != null){
			activeCall.holdCall();
		}
		
		return avSession.makeCall(remoteUri);
	}
	
	private boolean hangUpCall(){
		if(mAVSession != null){
			return mAVSession.hangUpCall();
		}
		return false;
	}
	
	private boolean acceptCall(){
		if(mAVSession != null){
			return mAVSession.acceptCall();
		}
		return false;
	}
	
	private void handleSipEvent(Intent intent){
		InviteState state;
		if(mAVSession == null){
			Log.e(TAG, "Invalid session object");
			return;
		}
		final String action = intent.getAction();
		if(SipService.ACTION_INVITE_EVENT.equals(action)){
			InviteEventArgs args = intent.getParcelableExtra(EventArgs.EXTRA_NAME);
			if(args == null){
				Log.e(TAG, "Invalid event args");
				return;
			}
			if(args.getSessionId() != mAVSession.getId()){
				return;
			}
			
			switch((state = mAVSession.getState())){
				case NONE:
				default:
					break;
					
				case INCOMING:
				case INPROGRESS:
				case REMOTE_RINGING:
					loadTryingView();
					break;
					
				case EARLY_MEDIA:
				case INCALL:
					if(state == InviteState.INCALL){
						mAVSession.setSpeakerphoneOn(false);
					}
					loadInCallView();
					// Send blank packets to open NAT pinhole
					if(mAVSession != null && mAVSession.getMediaType() == MediaType.AudioVideo || mAVSession.getMediaType() == MediaType.Video){
						mTimerBlankPacket.schedule(mTimerTaskBlankPacket, 0, 250);
					}
					mTimerInCall.schedule(mTimerTaskInCall, 0, 1000);
					break;
					
				case TERMINATING:
				case TERMINATED:
					ServiceManager.cancelAVCallNotif();
					if(mTvInfo != null){
						mTvInfo.setText(args.getPhrase());
					}	
					try{
						mTimerSuicide.schedule(mTimerTaskSuicide, new Date(new Date().getTime() + 1500));
					}
					catch(IllegalStateException ise){
						Log.d(TAG, ise.toString());
					}
					mTimerTaskInCall.cancel();
					mTimerBlankPacket.cancel();
					loadTermView();
					break;
			}
		}
	}
	
	private void loadView(){
		switch(mAVSession.getState()){
	        case INCOMING:
	        case INPROGRESS:
	        case REMOTE_RINGING:
	        case EARLY_MEDIA:
	        	loadTryingView();
	        	break;
	        	
	        case INCALL:
	        	loadInCallView();
	        	break;
	        	
	        case NONE:
	        case TERMINATING:
	        case TERMINATED:
	        default:
	        	loadTermView();
	        	break;
	    }
	}
	
	private void loadTryingView(){
		if(mCurrentView == ViewType.ViewTrying){
			return;
		}
		Log.d(TAG, "loadTryingView()");	
		
		final View view = mInflater.inflate(R.layout.view_call_trying, null);
		mTvInfo = (TextView)view.findViewById(R.id.view_call_trying_textView_info);
		@SuppressWarnings("unused")
		final TextView tvRemote = (TextView)view.findViewById(R.id.view_call_trying_textView_remote);
		final Button btPick = (Button)view.findViewById(R.id.view_call_trying_button_pick);
		final Button btHang = (Button)view.findViewById(R.id.view_call_trying_button_hang);
		btPick.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				acceptCall();
			}
		});
		btHang.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				hangUpCall();
			}
		});
		
		switch(mAVSession.getState()){
	        case INCOMING:
	        	mTvInfo.setText("Incoming Call");
	        	btPick.setText("Answer");
	        	btHang.setText("Decline");
	        	break;
	        case INPROGRESS:
	        case REMOTE_RINGING:
	        case EARLY_MEDIA:
	        default:
	        	mTvInfo.setText("Outgoing Call");
	        	btPick.setVisibility(View.GONE);
	        	btHang.setText("Cancel");
	        	break;
	    }
		
		mMainLayout.removeAllViews();
		mMainLayout.addView(view);
		ServiceManager.showAVCallNotif(R.drawable.phone_call_25, "Trying");
		mCurrentView = ViewType.ViewTrying;
	}
	
	private void loadInCallView(){
		if(mCurrentView == ViewType.ViewInCall){
			return;
		}
		Log.d(TAG, "loadInCallView()");
		
		final View audioView = mInflater.inflate(R.layout.view_call_incall_audio, null);
		mTvInfo = (TextView)audioView.findViewById(R.id.view_call_incall_audio_textView_info);
		final TextView tvRemote = (TextView)audioView.findViewById(R.id.view_call_incall_audio_textView_remote);
		final Button btHang = (Button)audioView.findViewById(R.id.view_call_incall_audio_button_hang);
		mTvDuration = (TextView)audioView.findViewById(R.id.view_call_incall_audio_textView_duration);
		
		btHang.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				hangUpCall();
			}
		});
		
		tvRemote.setText("John Doe");
		
		mMainLayout.removeAllViews();
		mMainLayout.addView(audioView);
		ServiceManager.showAVCallNotif(R.drawable.phone_call_25, "In Call");
		mCurrentView = ViewType.ViewInCall;
	}
	
	private void loadProxSensorView(){
		if(mCurrentView == ViewType.ViewProxSensor){
			return;
		}
		Log.d(TAG, "loadProxSensorView()");
		mMainLayout.removeAllViews();
		final View proxSensorView = mInflater.inflate(R.layout.view_call_proxsensor, null);
		mMainLayout.addView(proxSensorView);
		mCurrentView = ViewType.ViewProxSensor;
	}
	
	private void loadTermView(){
		if(mCurrentView == ViewType.ViewTermwait){
			return;
		}
		Log.d(TAG, "loadTermView()");
		// mMainLayout.removeAllViews();
		mCurrentView = ViewType.ViewTermwait;
	}
	
	private final TimerTask mTimerTaskInCall = new TimerTask(){
		@Override
		public void run() {
			if(mAVSession != null && mTvDuration != null){
				final Date date = new Date(new Date().getTime() - mAVSession.getStartTime());
				ScreenAV.this.runOnUiThread(new Runnable() {
					public void run() {
						mTvDuration.setText(durationTimerFormat.format(date));
					}});
			}
		}
	};
	
	private final TimerTask mTimerTaskBlankPacket = new TimerTask(){
		@Override
		public void run() {	
			Log.d(TAG,"Resending Blank Packet " +String.valueOf(countBlankPacket));
			if (countBlankPacket < 3) {
				if (mAVSession != null) {
					mAVSession.pushBlankPacket();
				}
				countBlankPacket++;
			}
			else {
				cancel();
				countBlankPacket=0;
			}
		}
	};
	
	private void cancelBlankPacket(){
		mTimerBlankPacket.cancel();
		countBlankPacket=0;
	}
	
	private final TimerTask mTimerTaskSuicide = new TimerTask(){
		@Override
		public void run() {
			ScreenAV.this.runOnUiThread(new Runnable() {
				public void run() {
					IBaseScreen currentScreen = mScreenService.getCurrentScreen();
					if(currentScreen != null && currentScreen.getId() == getId()){
						mScreenService.show(ScreenHome.class);
					}
					mScreenService.destroy(getId());
				}});
		}
	};
	
	
	
	private void startStopVideo(boolean bStart){
		if(mAVSession == null || (mAVSession.getMediaType() != MediaType.AudioVideo && mAVSession.getMediaType() != MediaType.Video)){
			return;
		}
		
		mAVSession.setSendingVideo(bStart);
		
//		this.llVideoLocal.removeAllViews();
//		if(bStart){
//			this.timerBlankPacket.cancel();
//			final View local_preview = mAVSession.startVideoProducerPreview();
//			if(local_preview != null){
//				final ViewParent viewParent = local_preview.getParent();
//				if(viewParent != null && viewParent instanceof ViewGroup){
//					((ViewGroup)(viewParent)).removeView(local_preview);
//				}
//				this.llVideoLocal.addView(local_preview);
//			}
//		}
//		this.llVideoLocal.setVisibility(bStart ? View.VISIBLE : View.GONE);
	}
	
	
	/**
	 * MyProxSensor
	 */
	static class MyProxSensor implements SensorEventListener
	{
		private final SensorManager mSensorManager;
		private Sensor mSensor;
		private final ScreenAV mAVScreen;
		private float mMaxRange;
		
		MyProxSensor(ScreenAV avScreen){
			mAVScreen = avScreen;
			mSensorManager = IMSDroid.getSensorManager();
		}
		
		void start(){
			if(mSensorManager != null && mSensor == null){
				if((mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)) != null){
					mMaxRange = mSensor.getMaximumRange();
					mSensorManager.registerListener(this, mSensor, SensorManager.SENSOR_DELAY_UI);
				}
			}
		}
		
		void stop(){
			if(mSensorManager != null && mSensor != null){
				mSensorManager.unregisterListener(this);
				mSensor = null;
			}
		}

		@Override
		public void onAccuracyChanged(Sensor sensor, int accuracy) {
		}

		@Override
		public void onSensorChanged(SensorEvent event) {
			try{ // Keep it until we get a phone supporting this feature
				if(mAVScreen == null){
					Log.e(ScreenAV.TAG, "invalid state");
					return;
				}
				if(event.values != null && event.values.length >0){
					if(event.values[0] < this.mMaxRange){
						Log.d(TAG, "reenableKeyguard()");
						mAVScreen.loadProxSensorView();
					}
					else{
						Log.d(TAG, "disableKeyguard()");
						mAVScreen.loadView();
					}
				}
			}
			catch(Exception e){
				e.printStackTrace();
			}
		}
	}
}