/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.ui;

import java.security.SecureRandom;
import java.util.Random;
import org.sensorhub.api.command.CommandData;
import org.sensorhub.api.command.IStreamingControlInterface;
import org.sensorhub.impl.system.CommandStreamTransactionHandler;
import org.sensorhub.impl.system.SystemDatabaseTransactionHandler;
import org.sensorhub.ui.api.UIConstants;
import com.vaadin.ui.Alignment;
import com.vaadin.ui.Button;
import com.vaadin.ui.Button.ClickEvent;
import com.vaadin.ui.Button.ClickListener;
import net.opengis.swe.v20.DataComponent;


@SuppressWarnings("serial")
public class SWEControlForm extends SWEEditForm
{
    transient IStreamingControlInterface controlInput;
    transient DataComponent controlSink;
    transient CommandStreamTransactionHandler commandTxn;
    transient String userID;
    transient Random random = new SecureRandom();
    
    
    public SWEControlForm(final IStreamingControlInterface controlInput) { this(controlInput, null); }

    public SWEControlForm(final IStreamingControlInterface controlInput, final ClickListener submitListener)
    {
        super(controlInput.getCommandDescription().copy());
        this.controlInput = controlInput;
        this.component.assignNewDataBlock();
        buildForm(submitListener);
    }
    
    public SWEControlForm(final DataComponent params) { this(params, null); }

    public SWEControlForm(final DataComponent params, ClickListener submitListener)
    {
        super(params.copy());
        this.addSpacing = true;
        this.controlSink = params;
        this.component.setData(params.getData());
        buildForm(submitListener);
    }
    
    @Override
    public void attach()
    {
        super.attach();
        
        if (controlInput != null)
        {
            var ui = ((AdminUI)this.getUI());
            
            var user = ui.getSecurityHandler().getCurrentUser();
            this.userID = user != null ? user.getId() : "adminUI";
            
            var sysUID = controlInput.getParentProducer().getUniqueIdentifier();
            var eventBus = ui.getParentHub().getEventBus();
            var db = ui.getParentHub().getDatabaseRegistry().getFederatedDatabase();
            var sysTxnHandler = new SystemDatabaseTransactionHandler(eventBus, db);
            this.commandTxn = sysTxnHandler.getCommandStreamHandler(sysUID, controlInput.getName());
        }
    }


    protected void buildForm(ClickListener submitListener)
    {
        super.buildForm();
        
        // send button
        Button sendBtn = new Button("Send Command");
        sendBtn.addStyleName(UIConstants.STYLE_SMALL);
        addComponent(sendBtn);
        setComponentAlignment(sendBtn, Alignment.MIDDLE_LEFT);
        sendBtn.addClickListener(new ClickListener()
        {   
            private static final long serialVersionUID = 1L;

            @Override
            public void buttonClick(ClickEvent event)
            {
                try
                {
                    var cmdData = component.getData().clone();
                    
                    if (commandTxn != null)
                    {
                        var cmd = new CommandData.Builder()
                            .withSender(userID)
                            .withCommandStream(commandTxn.getCommandStreamKey().getInternalID())
                            .withParams(cmdData)
                            .build();
                        commandTxn.submitCommand(random.nextLong(), cmd, null);
                    }
                    else
                        controlSink.setData(cmdData);
                }
                catch (Exception e)
                {
                    DisplayUtils.showErrorPopup("Error while sending command to sensor", e);
                }
            }
        });

        if (submitListener != null)
            sendBtn.addClickListener(submitListener);
    }
}
