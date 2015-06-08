package com.cordova.plugins.sms;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Telephony;
import android.R;
import android.telephony.SmsManager;
import android.util.Base64;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;

public class Sms extends CordovaPlugin {
	public final String ACTION_SEND_SMS = "send";
	private static final String INTENT_FILTER_SMS_SENT = "SMS_SENT";

	@Override
	public boolean execute(String action, final JSONArray args, final CallbackContext callbackContext) throws JSONException {

		if (action.equals(ACTION_SEND_SMS)) {
            		cordova.getThreadPool().execute(new Runnable() {
                		@Override
                		public void run() {
                    			try {
                        			//parsing arguments
                        			String phoneNumber = args.getJSONArray(0).join(";").replace("\"", "");
                        			String message = args.getString(1);
                        			String audioFile = args.getString(2);
                        			String fileType = args.getString(3);
						String method = args.getString(4);									
                        			boolean replaceLineBreaks = Boolean.parseBoolean(args.getString(3));

                        			// replacing \n by new line if the parameter replaceLineBreaks is set to true
                        			if (replaceLineBreaks) {
                            				message = message.replace("\\n", System.getProperty("line.separator"));
                        			}
                        			if (!checkSupport()) {
                            				callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, "SMS not supported on this platform"));
                            				return;
                        			}
                        			if (method.equalsIgnoreCase("INTENT")) {
                            				// used for attachments
											invokeSMSIntent(phoneNumber, message, audioFile, fileType);
                            				// always passes success back to the app
                            				callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK));
                        			} else if (method.equalsIgnoreCase("")) {
											// used for invites without attachments
											send(callbackContext, phoneNumber, message);
									} else {
                            				// does not send audio file - change message to contact me
											message = "I have something to tell you. Call me.";
											send(callbackContext, phoneNumber, message);
                        			}
                        			return;
                    			} catch (JSONException ex) {
                        			callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.JSON_EXCEPTION));
                    			}
                		}
            		});
            		return true;
		}
		return false;
	}

	private boolean checkSupport() {
		Activity ctx = this.cordova.getActivity();
		return ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY);
	}

	@SuppressLint("NewApi")
	private void invokeSMSIntent(String phoneNumber, String message, String audioFile, String fileType) {
		Intent sendIntent;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
			// send user directly to mms - no chooser
			String defaultSmsPackageName = Telephony.Sms.getDefaultSmsPackage(this.cordova.getActivity());
			sendIntent = new Intent(Intent.ACTION_SEND); 						
			File f=new File(Environment.getExternalStorageDirectory().getAbsolutePath()+"/"+audioFile);															
			sendIntent.putExtra("address", phoneNumber);			
			sendIntent.putExtra("sms_body", message);												
			sendIntent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(f));
			sendIntent.setType(fileType);  
			if (defaultSmsPackageName != null) {
				sendIntent.setPackage(defaultSmsPackageName);
			}
			this.cordova.getActivity().startActivity(sendIntent);		
		} else {
			// send user to chooser - needs to select messenging app			
			sendIntent = new Intent(Intent.ACTION_SEND); 						
			File f=new File(Environment.getExternalStorageDirectory().getAbsolutePath()+"/"+audioFile);															
			sendIntent.putExtra("address", phoneNumber);			
			sendIntent.putExtra("sms_body", message);												
			sendIntent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(f));
			sendIntent.setType(fileType);  											
			this.cordova.getActivity().startActivity(Intent.createChooser(sendIntent, "Select Text Messaging App"));		
		}		
	}

	private void send(final CallbackContext callbackContext, String phoneNumber, String message) {
		SmsManager manager = SmsManager.getDefault();
		final ArrayList<String> parts = manager.divideMessage(message);

		// by creating this broadcast receiver we can check whether or not the SMS was sent			
		final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
				
			boolean anyError = false; //use to detect if one of the parts failed
			int partsCount = parts.size(); //number of parts to send

			@Override
			public void onReceive(Context context, Intent intent) {
				switch (getResultCode()) {
				case SmsManager.STATUS_ON_ICC_SENT:
				case Activity.RESULT_OK:
					break;
				case SmsManager.RESULT_ERROR_GENERIC_FAILURE:
				case SmsManager.RESULT_ERROR_NO_SERVICE:
				case SmsManager.RESULT_ERROR_NULL_PDU:
				case SmsManager.RESULT_ERROR_RADIO_OFF:
					anyError = true;
					break;
				}
				// trigger the callback only when all the parts have been sent
				partsCount--;
				if (partsCount == 0) {
					if (anyError) {
						callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR));
					} else {
						callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK));
					}
					cordova.getActivity().unregisterReceiver(this);
				}
			}
		};

		// randomize the intent filter action to avoid using the same receiver
		String intentFilterAction = INTENT_FILTER_SMS_SENT + java.util.UUID.randomUUID().toString();
		this.cordova.getActivity().registerReceiver(broadcastReceiver, new IntentFilter(intentFilterAction));

		PendingIntent sentIntent = PendingIntent.getBroadcast(this.cordova.getActivity(), 0, new Intent(intentFilterAction), 0);

		// depending on the number of parts we send a text message or multi parts
		if (parts.size() > 1) {
			ArrayList<PendingIntent> sentIntents = new ArrayList<PendingIntent>();
			for (int i = 0; i < parts.size(); i++) {
				sentIntents.add(sentIntent);
			}
			manager.sendMultipartTextMessage(phoneNumber, null, parts, sentIntents, null);
		}
		else {
			manager.sendTextMessage(phoneNumber, null, message, sentIntent, null);
		}
	}
}


	private void send(final CallbackContext callbackContext, String phoneNumber, String message) {
		SmsManager manager = SmsManager.getDefault();
		final ArrayList<String> parts = manager.divideMessage(message);

		// by creating this broadcast receiver we can check whether or not the SMS was sent			
		final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
				
			boolean anyError = false; //use to detect if one of the parts failed
			int partsCount = parts.size(); //number of parts to send

			@Override
			public void onReceive(Context context, Intent intent) {
				switch (getResultCode()) {
				case SmsManager.STATUS_ON_ICC_SENT:
				case Activity.RESULT_OK:
					break;
				case SmsManager.RESULT_ERROR_GENERIC_FAILURE:
				case SmsManager.RESULT_ERROR_NO_SERVICE:
				case SmsManager.RESULT_ERROR_NULL_PDU:
				case SmsManager.RESULT_ERROR_RADIO_OFF:
					anyError = true;
					break;
				}
				// trigger the callback only when all the parts have been sent
				partsCount--;
				if (partsCount == 0) {
					if (anyError) {
						callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR));
					} else {
						callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK));
					}
					cordova.getActivity().unregisterReceiver(this);
				}
			}
		};

		// randomize the intent filter action to avoid using the same receiver
		String intentFilterAction = INTENT_FILTER_SMS_SENT + java.util.UUID.randomUUID().toString();
		this.cordova.getActivity().registerReceiver(broadcastReceiver, new IntentFilter(intentFilterAction));

		PendingIntent sentIntent = PendingIntent.getBroadcast(this.cordova.getActivity(), 0, new Intent(intentFilterAction), 0);

		// depending on the number of parts we send a text message or multi parts
		if (parts.size() > 1) {
			ArrayList<PendingIntent> sentIntents = new ArrayList<PendingIntent>();
			for (int i = 0; i < parts.size(); i++) {
				sentIntents.add(sentIntent);
			}
			manager.sendMultipartTextMessage(phoneNumber, null, parts, sentIntents, null);
		}
		else {
			manager.sendTextMessage(phoneNumber, null, message, sentIntent, null);
		}
	}
}
