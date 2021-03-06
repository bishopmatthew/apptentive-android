/*
 * Copyright (c) 2013, Apptentive, Inc. All Rights Reserved.
 * Please refer to the LICENSE file for the terms and conditions
 * under which redistribution and use of this file is permitted.
 */

package com.apptentive.android.sdk.module.messagecenter;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.view.ViewGroup;
import android.widget.Toast;
import com.apptentive.android.sdk.*;
import com.apptentive.android.sdk.model.*;
import com.apptentive.android.sdk.module.messagecenter.view.MessageCenterIntroDialog;
import com.apptentive.android.sdk.module.messagecenter.view.MessageCenterThankYouDialog;
import com.apptentive.android.sdk.module.messagecenter.view.MessageCenterView;
import com.apptentive.android.sdk.module.metric.MetricModule;
import com.apptentive.android.sdk.storage.ApptentiveDatabase;
import com.apptentive.android.sdk.storage.PersonManager;
import com.apptentive.android.sdk.util.Constants;
import com.apptentive.android.sdk.util.Util;

import java.util.List;


/**
 * @author Sky Kelsey
 */
public class ApptentiveMessageCenter {

	protected static MessageCenterView messageCenterView;
	private static boolean pollForMessages = false;
	private static Trigger trigger;

	public static void show(Activity activity, boolean forced) {
		show(activity, forced ? Trigger.message_center : Trigger.enjoyment_dialog);
	}

	public static void show(Activity activity, Trigger reason) {
		SharedPreferences prefs = activity.getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE);
		Configuration conf = Configuration.load(activity);
		boolean enableMessageCenter = conf.isMessageCenterEnabled();
		boolean emailRequired = conf.isMessageCenterEmailRequired();
		boolean shouldShowIntroDialog = !enableMessageCenter || prefs.getBoolean(Constants.PREF_KEY_MESSAGE_CENTER_SHOULD_SHOW_INTRO_DIALOG, true);
		// TODO: What if there is an incoming message that is unread? Shouldn't they see the Message Center right away?
		if (shouldShowIntroDialog) {
			showIntroDialog(activity, reason, emailRequired);
		} else {
			ApptentiveMessageCenter.trigger = reason;
			Intent intent = new Intent();
			intent.setClass(activity, ViewActivity.class);
			intent.putExtra("module", ViewActivity.Module.MESSAGE_CENTER.toString());
			activity.startActivity(intent);
			activity.overridePendingTransition(R.anim.slide_up_in, R.anim.slide_down_out);
		}
	}

	/**
	 * @param context The Activity Context that launched this view.
	 */
	public static void doShow(final Context context) {
		if (!(context instanceof Activity)) {
			Log.e(ApptentiveMessageCenter.class.getSimpleName() + " must be initialized with an Activity Context.");
			return;
		}

		MetricModule.sendMetric(context, Event.EventLabel.message_center__launch, (trigger == null ? null : trigger.name()));

		MessageCenterView.OnSendMessageListener onSendMessagelistener = new MessageCenterView.OnSendMessageListener() {
			public void onSendTextMessage(String text) {
				final TextMessage message = new TextMessage();
				message.setBody(text);
				message.setRead(true);
				MessageManager.sendMessage(context, message);
				messageCenterView.post(new Runnable() {
					public void run() {
						messageCenterView.addMessage(message);
					}
				});
				scrollToBottom();
			}

			public void onSendFileMessage(Uri uri) {
				// First, create the file, and populate some metadata about it.
				final FileMessage message = new FileMessage();
				boolean successful = message.createStoredFile(context, uri.toString());
				if (successful) {
					message.setRead(true);
					// Finally, send out the message.
					MessageManager.sendMessage(context, message);
					messageCenterView.post(new Runnable() {
						public void run() {
							messageCenterView.addMessage(message);
						}
					});
					scrollToBottom();
				} else {
					Log.e("Unable to send file.");
					Toast.makeText(messageCenterView.getContext(), "Unable to send file.", Toast.LENGTH_SHORT);
				}
			}
		};

		messageCenterView = new MessageCenterView((Activity) context, onSendMessagelistener);

		// Remove an existing MessageCenterView and replace it with this, if it exists.
		if (messageCenterView.getParent() != null) {
			((ViewGroup) messageCenterView.getParent()).removeView(messageCenterView);
		}
		((Activity) context).setContentView(messageCenterView);

		// Display the messages we already have for starters.
		messageCenterView.setMessages(MessageManager.getMessages(context));

		// This listener will run when messages are retrieved from the server, and will start a new thread to update the view.
		final MessageManager.MessagesUpdatedListener listener = new MessageManager.MessagesUpdatedListener() {
			public void onMessagesUpdated() {
				messageCenterView.post(new Runnable() {
					public void run() {
						List<Message> messages = MessageManager.getMessages(context);
						messageCenterView.setMessages(messages);
						scrollToBottom();
						Apptentive.notifyUnreadMessagesListener(MessageManager.getUnreadMessageCount(context));
					}
				});
			}
		};

		// Give the MessageCenterView a callback when a message is sent.
		MessageManager.setInternalSentMessageListener(messageCenterView);

		Configuration configuration = Configuration.load(context);
		final int fgPoll = configuration.getMessageCenterFgPoll() * 1000;
		Log.d("Starting Message Center polling every %d millis", fgPoll);
		pollForMessages = true;
		new Thread() {
			@Override
			public void run() {
				while (pollForMessages) {
					// TODO: Check for data connection present before trying.
					MessageManager.fetchAndStoreMessages(context, listener);
					try {
						Thread.sleep(fgPoll);
					} catch (InterruptedException e) {
						Log.w("Message Center polling thread interrupted.");
						return;
					}
				}
				Log.d("Stopping Message Center polling thread.");
			}
		}.start();

		scrollToBottom();
	}

	static void showIntroDialog(final Activity activity, final Trigger reason, boolean emailRequired) {
		final MessageCenterIntroDialog dialog = new MessageCenterIntroDialog(activity);
		dialog.setEmailRequired(emailRequired);

		String personEnteredEmail = PersonManager.loadPersonEmail(activity);
		String personInitialEmail = PersonManager.loadInitialPersonEmail(activity);
		if (Util.isEmpty(personEnteredEmail)) {
			if (!Util.isEmpty(personInitialEmail)) {
				dialog.setEmailFieldHidden(false);
				dialog.prePopulateEmail(personInitialEmail);
			}
		} else {
			dialog.setEmailFieldHidden(true);
		}
		dialog.setCanceledOnTouchOutside(false);

		dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
			@Override
			public void onCancel(DialogInterface dialogInterface) {
				MetricModule.sendMetric(activity, Event.EventLabel.message_center__intro__cancel);
				dialog.dismiss();
			}
		});

		dialog.setOnSendListener(new MessageCenterIntroDialog.OnSendListener() {
			@Override
			public void onSend(String email, String message) {
				SharedPreferences prefs = activity.getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE);
				prefs.edit().putBoolean(Constants.PREF_KEY_MESSAGE_CENTER_SHOULD_SHOW_INTRO_DIALOG, false).commit();
				// Save the email.
				if (dialog.isEmailFieldVisible()) {
					if (email != null && email.length() != 0) {
						PersonManager.storePersonEmail(activity, email);
						Person person = PersonManager.storePersonAndReturnDiff(activity);
						if (person != null) {
							Log.d("Person was updated.");
							Log.v(person.toString());
							ApptentiveDatabase.getInstance(activity).addPayload(person);
						} else {
							Log.d("Person was not updated.");
						}
					}
				}
				// Send the message.
				final TextMessage textMessage = new TextMessage();
				textMessage.setBody(message);
				textMessage.setRead(true);
				MessageManager.sendMessage(activity, textMessage);
				MetricModule.sendMetric(activity, Event.EventLabel.message_center__intro__send);
				dialog.dismiss();

				final MessageCenterThankYouDialog messageCenterThankYouDialog = new MessageCenterThankYouDialog(activity);
				messageCenterThankYouDialog.setValidEmailProvided(email != null && Util.isEmailValid(email));
				messageCenterThankYouDialog.setOnChoiceMadeListener(new MessageCenterThankYouDialog.OnChoiceMadeListener() {
					@Override
					public void onNo() {
						MetricModule.sendMetric(activity, Event.EventLabel.message_center__thank_you__close);
					}

					@Override
					public void onYes() {
						MetricModule.sendMetric(activity, Event.EventLabel.message_center__thank_you__messages);
						show(activity, reason);
					}
				});
				MetricModule.sendMetric(activity, Event.EventLabel.message_center__thank_you__launch);
				messageCenterThankYouDialog.show();
			}
		});

		String appDisplayName = Configuration.load(activity).getAppDisplayName();
		switch (reason) {
			case enjoyment_dialog:
				dialog.setTitle(R.string.apptentive_intro_dialog_title_no_love);
				dialog.setBody(activity.getResources().getString(R.string.apptentive_intro_dialog_body, appDisplayName));
				break;
			case message_center:
				dialog.setTitle(R.string.apptentive_intro_dialog_title_default);
				dialog.setBody(activity.getResources().getString(R.string.apptentive_intro_dialog_body, appDisplayName));
				break;
			default:
				return;
		}
		MetricModule.sendMetric(activity, Event.EventLabel.message_center__intro__launch, reason.name());
		dialog.show();
	}

	public static void scrollToBottom() {
		messageCenterView.scrollMessageListViewToBottom();
	}

	public static void onStop(@SuppressWarnings("unused") Context context) {
		pollForMessages = false;
	}

	public static void onBackPressed(Activity activity) {
		MetricModule.sendMetric(activity, Event.EventLabel.message_center__close);
		activity.finish();
		activity.overridePendingTransition(R.anim.slide_up_in, R.anim.slide_down_out);

	}

	enum Trigger {
		enjoyment_dialog,
		message_center
	}
}
