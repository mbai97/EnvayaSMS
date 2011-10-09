
package org.envaya.sms;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.telephony.SmsManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import org.apache.http.message.BasicNameValuePair;
import org.envaya.sms.receiver.DequeueOutgoingMessageReceiver;
import org.envaya.sms.task.HttpTask;

public class Outbox {
    private Map<Uri, OutgoingMessage> outgoingMessages = new HashMap<Uri, OutgoingMessage>();    

    private App app;   

    // number of outgoing messages that are currently being sent and waiting for
    // messageSent or messageFailed to be called
    private int numSendingOutgoingMessages = 0;
     
    // cache of next time we can send the first message in queue without
    // exceeding android sending limit
    private long nextValidOutgoingTime;   

    // enqueue outgoing messages in descending order by priority, ascending by local id
    // (order in which message was received)
    private PriorityQueue<OutgoingMessage> outgoingQueue = new PriorityQueue<OutgoingMessage>(10, 
        new Comparator<OutgoingMessage>() { 
            public int compare(OutgoingMessage t1, OutgoingMessage t2)
            {
                int pri2 = t2.getPriority();
                int pri1 = t1.getPriority();
                
                if (pri1 != pri2)
                {
                    return pri2 - pri1;
                }
                
                int order2 = t2.getLocalId();
                int order1 = t1.getLocalId();
                
                return order1 - order2;
            }
        }            
    );
    
    public Outbox(App app)
    {
        this.app = app;
    }    
    
    private void notifyMessageStatus(OutgoingMessage sms, String status, String errorMessage) {
        String serverId = sms.getServerId();
               
        String logMessage;
        if (status.equals(App.STATUS_SENT)) {
            logMessage = "sent successfully";
        } else if (status.equals(App.STATUS_FAILED)) {
            logMessage = "could not be sent (" + errorMessage + ")";
        } else {
            logMessage = "queued";
        }
        String smsDesc = sms.getLogName();

        if (serverId != null) {
            app.log("Notifying server " + smsDesc + " " + logMessage);

            new HttpTask(app,
                new BasicNameValuePair("id", serverId),
                new BasicNameValuePair("status", status),
                new BasicNameValuePair("error", errorMessage),
                new BasicNameValuePair("action", App.ACTION_SEND_STATUS)                    
            ).execute();
        } else {
            app.log(smsDesc + " " + logMessage);
        }
    }
    
    public synchronized void retryAll() 
    {
        nextValidOutgoingTime = 0;        

        for (OutgoingMessage sms : outgoingMessages.values()) {
            enqueueMessage(sms);
        }
        maybeDequeueMessage();
    }  

    public OutgoingMessage getMessage(Uri uri)
    {
        return outgoingMessages.get(uri);
    }        
    
    public synchronized void messageSent(OutgoingMessage sms)
    {
        sms.setProcessingState(OutgoingMessage.ProcessingState.Sent);
        
        notifyMessageStatus(sms, App.STATUS_SENT, "");
        
        outgoingMessages.remove(sms.getUri());
        
        notifyChanged();
        
        numSendingOutgoingMessages--;
        maybeDequeueMessage();
    }
    
    public synchronized void messageFailed(OutgoingMessage sms, String error)
    {
        if (sms.scheduleRetry()) 
        {
            sms.setProcessingState(OutgoingMessage.ProcessingState.Scheduled);
        }
        else
        {
            sms.setProcessingState(OutgoingMessage.ProcessingState.None);   
        }
        notifyChanged();
        notifyMessageStatus(sms, App.STATUS_FAILED, error);

        numSendingOutgoingMessages--;
        maybeDequeueMessage();        
    }

    public synchronized void sendMessage(OutgoingMessage sms) {
        
        String to = sms.getTo();
        if (to == null || to.length() == 0)
        {
            notifyMessageStatus(sms, App.STATUS_FAILED, 
                    "Destination address is empty");
            return;
        }        
        
        if (app.isTestMode() && !app.isTestPhoneNumber(to))
        {
            // this is mostly to prevent accidentally sending real messages to
            // random people while testing...        
            notifyMessageStatus(sms, App.STATUS_FAILED,
                    "Destination number is not in list of test senders");
            return;
        }
        
        String messageBody = sms.getMessageBody();
        
        if (messageBody == null || messageBody.length() == 0)
        {
            notifyMessageStatus(sms, App.STATUS_FAILED, 
                    "Message body is empty");
            return;
        }               
        
        Uri uri = sms.getUri();
        if (outgoingMessages.containsKey(uri)) {
            app.debug("Duplicate outgoing " + sms.getLogName() + ", skipping");
            return;
        }

        outgoingMessages.put(uri, sms);        
        enqueueMessage(sms);
    }
    
    public synchronized void deleteMessage(OutgoingMessage message)
    {
        outgoingMessages.remove(message.getUri());
        
        if (message.getProcessingState() == OutgoingMessage.ProcessingState.Queued)
        {
            outgoingQueue.remove(message);
        }
        else if (message.getProcessingState() == OutgoingMessage.ProcessingState.Sending)
        {
            numSendingOutgoingMessages--;
        }        
        
        notifyMessageStatus(message, App.STATUS_FAILED, 
                "deleted by user");
        app.log(message.getDescription() + " deleted");
        notifyChanged();
    }    
    
    public synchronized void maybeDequeueMessage()
    {
        long now = System.currentTimeMillis();        
        if (nextValidOutgoingTime <= now && numSendingOutgoingMessages < 2)
        {
            OutgoingMessage sms = outgoingQueue.peek();
            
            if (sms == null)
            {
                return;
            }
            
            SmsManager smgr = SmsManager.getDefault();
            ArrayList<String> bodyParts = smgr.divideMessage(sms.getMessageBody());
            
            int numParts = bodyParts.size();
            
            if (numParts > App.OUTGOING_SMS_MAX_COUNT)
            {
                outgoingQueue.poll();
                outgoingMessages.remove(sms.getUri());
                notifyMessageStatus(sms, App.STATUS_FAILED, 
                        "Message has too many parts ("+(numParts)+")");
                return;
            }
            
            String packageName = app.chooseOutgoingSmsPackage(numParts);            
            
            if (packageName == null)
            {            
                nextValidOutgoingTime = app.getNextValidOutgoingTime(numParts);                
                                
                if (nextValidOutgoingTime <= now) // should never happen
                {
                    nextValidOutgoingTime = now + 2000;
                }
                
                long diff = nextValidOutgoingTime - now;
                
                app.log("Waiting for " + (diff/1000) + " seconds");
                
                AlarmManager alarm = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);

                Intent intent = new Intent(app, DequeueOutgoingMessageReceiver.class);
                
                PendingIntent pendingIntent = PendingIntent.getBroadcast(app,
                    0,
                    intent,
                    0);

                alarm.set(
                    AlarmManager.RTC_WAKEUP,
                    nextValidOutgoingTime,
                    pendingIntent);
                
                return;
            }
            
            outgoingQueue.poll();            
            numSendingOutgoingMessages++;
            
            sms.setProcessingState(OutgoingMessage.ProcessingState.Sending);
            
            sms.trySend(bodyParts, packageName);
            notifyChanged();
        }          
    }
    
    public synchronized void enqueueMessage(OutgoingMessage message) 
    {
        OutgoingMessage.ProcessingState state = message.getProcessingState();
        
        if (state == OutgoingMessage.ProcessingState.Scheduled
            || state == OutgoingMessage.ProcessingState.None) 
        {        
            outgoingQueue.add(message);
            message.setProcessingState(OutgoingMessage.ProcessingState.Queued);
            notifyChanged();
            maybeDequeueMessage();                       
        }
    }
    
    private void notifyChanged()
    {
        app.sendBroadcast(new Intent(App.OUTBOX_CHANGED_INTENT));
    }    
    
    public synchronized int size() {        
        return outgoingMessages.size();
    }    
    
    public synchronized Collection<OutgoingMessage> getMessages()
    {
        return outgoingMessages.values();
    }
}