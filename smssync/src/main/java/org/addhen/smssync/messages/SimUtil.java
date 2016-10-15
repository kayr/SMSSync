package org.addhen.smssync.messages;

import static org.addhen.smssync.messages.ReflectionUtils.invokeStaticMethod;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import android.telephony.SubscriptionInfo;
import org.addhen.smssync.util.Util;

import android.app.PendingIntent;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;
import android.util.Log;

/**
 * Created by Apipas on 6/4/15.
 */
public class SimUtil {

	public static boolean sendSMS(Context ctx, String toNum, ArrayList<String> smsTextlist,
			ArrayList<PendingIntent> sentIntentList, ArrayList<PendingIntent> deliveryIntentList) {

		List<SimInfo> simInfoList = getSIMInfo(ctx);
		if (simInfoList.size() > 1) {

			boolean isAirtel = toNum.startsWith("+25670") || toNum.startsWith("+25675");
			boolean isMtn = toNum.startsWith("+25677") || toNum.startsWith("+25678");

			SimInfo airSim = null, mtnSim = null;

			for (SimInfo sim : simInfoList) {
				String displayName = sim.getDisplay_name().toLowerCase();
				if (displayName.contains("mtn")) {
					mtnSim = sim;
				} else if (displayName.contains("warid") || displayName.contains("celtel")) {
					airSim = sim;
				}
			}

			SimInfo selectedSim = null;
			if (isMtn && null != mtnSim) {
				selectedSim = mtnSim;
			} else if (isAirtel && null != airSim) {
				selectedSim = airSim;
			}

			if (selectedSim != null) {
				Util.logActivities(ctx,
						"DualSim: Sending SMS to(" + toNum + ") Using: [" + selectedSim.getDisplay_name() + "]");
				return sendSMS(ctx, selectedSim.getSlot(), toNum, smsTextlist, sentIntentList, deliveryIntentList);
			}

			return sendSMSWithDefault(toNum, smsTextlist, sentIntentList, deliveryIntentList);

		} else {
			return sendSMSWithDefault(toNum, smsTextlist, sentIntentList, deliveryIntentList);
		}

	}

	private static boolean sendSMSWithDefault(String toNum, ArrayList<String> smsTextlist,
			ArrayList<PendingIntent> sentIntentList, ArrayList<PendingIntent> deliveryIntentList) {
		SmsManager sms = SmsManager.getDefault();
		sms.sendMultipartTextMessage(toNum, null, smsTextlist, sentIntentList, deliveryIntentList);
		return true;
	}

	private static boolean sendSMS(Context ctx, int simID, String toNum, ArrayList<String> smsTextlist,
			ArrayList<PendingIntent> sentIntentList, ArrayList<PendingIntent> deliveryIntentList) {

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
			SubscriptionInfo subscriptionInfo = SubscriptionManager.from(ctx).getActiveSubscriptionInfoForSimSlotIndex(simID);
			int subscriptionId = subscriptionInfo.getSubscriptionId();
			SmsManager smsManagerForSubscriptionId = SmsManager.getSmsManagerForSubscriptionId(subscriptionId);
			smsManagerForSubscriptionId.sendMultipartTextMessage(toNum, null, smsTextlist, sentIntentList,
					deliveryIntentList);

		} else {

			boolean sentWithK3Api = sentSMSWithLenovoK3API(ctx, simID, toNum, smsTextlist, sentIntentList,
					deliveryIntentList);

			if (sentWithK3Api) {
				Util.logActivities(ctx, "DualSim: Message to(" + toNum + ")  Sent With LenovoK3 API");
				return true;
			}

			Util.logActivities(ctx, "DualSim: SMS Failed: LenovoK3 API 1");

			boolean sentWithReflection = sendMultipartTextSMS(ctx, simID, toNum, null, smsTextlist, sentIntentList,
					deliveryIntentList);

			if (sentWithReflection) {
				Util.logActivities(ctx, "DualSim: Message to(" + toNum + ") Sent With Reflection API 1");
				return true;
			}
			Util.logActivities(ctx, "SMS Failed: Reflection API 1");

		}

		Util.logActivities(ctx, "Failed to send SMS to(" + toNum + ")  with DualSimAPi..Falling back to default");
		sendSMSWithDefault(toNum, smsTextlist, sentIntentList, deliveryIntentList);
		return true;
	}

	private static boolean sentSMSWithLenovoK3API(Context ctx, int simID, String toNum, ArrayList<String> smsTextlist,
			ArrayList<PendingIntent> sentIntentList, ArrayList<PendingIntent> deliveryIntentList) {

		try {

			// SubscriptionManager.getActiveSubId()
			// SubscriptionManager.getActiveSubInfoList()

			long[] subIds = (long[]) invokeStaticMethod(SubscriptionManager.class, "getSubId", simID);

			long subId;
			if (subIds != null) {
				subId = subIds[0];
			} else {
				subId = (long) invokeStaticMethod(SubscriptionManager.class, "getDefaultSubId");
			}

			SmsManager sms = (SmsManager) invokeStaticMethod(SmsManager.class, "getSmsManagerForSubscriber", subId);

			sms.sendMultipartTextMessage(toNum, null, smsTextlist, sentIntentList, deliveryIntentList);

			return true;
		} catch (Exception e) {
			Util.logActivities(ctx, "Failed To Send SMS to(" + toNum + ") With Dual Sim: " + e.getMessage());
		}

		return false;
	}

	private static boolean sendMultipartTextSMS(Context ctx, int simID, String toNum, String centerNum,
			ArrayList<String> smsTextlist, ArrayList<PendingIntent> sentIntentList,
			ArrayList<PendingIntent> deliveryIntentList) {
		String name;
		try {
			if (simID == 0) {
				name = "isms";
				// for model : "Philips T939" name = "isms0"
			} else if (simID == 1) {
				name = "isms2";
			} else {
				throw new Exception("can not get service which for sim '" + simID + "', only 0,1 accepted as values");
			}

			Method method = Class.forName("android.os.ServiceManager").getDeclaredMethod("getService", String.class);
			method.setAccessible(true);
			Object param = method.invoke(null, name);

			method = Class.forName("com.android.internal.telephony.ISms$Stub").getDeclaredMethod("asInterface",
					IBinder.class);
			method.setAccessible(true);
			Object stubObj = method.invoke(null, param);
			if (Build.VERSION.SDK_INT < 18) {
				method = stubObj.getClass().getMethod("sendMultipartText", String.class, String.class, List.class,
						List.class, List.class);
				method.invoke(stubObj, toNum, centerNum, smsTextlist, sentIntentList, deliveryIntentList);
			} else {
				method = stubObj.getClass().getMethod("sendMultipartText", String.class, String.class, String.class,
						List.class, List.class, List.class);
				method.invoke(stubObj, ctx.getPackageName(), toNum, centerNum, smsTextlist, sentIntentList,
						deliveryIntentList);
			}
			return true;
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
			Log.e("apipas", "ClassNotFoundException:" + e.getMessage());
		} catch (NoSuchMethodException e) {
			e.printStackTrace();
			Log.e("apipas", "NoSuchMethodException:" + e.getMessage());
		} catch (InvocationTargetException e) {
			e.printStackTrace();
			Log.e("apipas", "InvocationTargetException:" + e.getMessage());
		} catch (IllegalAccessException e) {
			e.printStackTrace();
			Log.e("apipas", "IllegalAccessException:" + e.getMessage());
		} catch (Exception e) {
			e.printStackTrace();
			Log.e("apipas", "Exception:" + e.getMessage());
		}
		return false;
	}

	public static boolean isDualSim(Context context) {
		return getSIMInfo(context).size() > 1;
	}

	public static List<SimInfo> getSIMInfo(Context context) {
		List<SimInfo> simInfoList = new ArrayList<>();
		Uri URI_TELEPHONY = Uri.parse("content://telephony/siminfo/");
		Cursor c = context.getContentResolver().query(URI_TELEPHONY, null, null, null, null);
		if (null != c && c.moveToFirst()) {
			do {
				int id = c.getInt(c.getColumnIndex("_id"));

				int slot1 = c.getColumnIndex("slot");
				slot1 = (slot1 == -1 ? c.getColumnIndex("sim_id") : slot1);
				int slot = c.getInt(slot1);

				String display_name = c.getString(c.getColumnIndex("display_name"));
				String icc_id = c.getString(c.getColumnIndex("icc_id"));
				SimInfo simInfo = new SimInfo(id, display_name, icc_id, slot);

				if (slot >= 0) {
					simInfoList.add(simInfo);
				}
			} while (c.moveToNext());

			c.close();
		}

		return simInfoList;
	}

}