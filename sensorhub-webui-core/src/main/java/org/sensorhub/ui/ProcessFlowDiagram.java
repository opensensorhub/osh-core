/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2017 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.ui;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.sensorhub.api.data.IDataProducer;
import org.sensorhub.api.processing.ProcessingException;
import org.sensorhub.impl.processing.StreamDataSource;
import org.vast.data.DataIterator;
import org.vast.process.IProcessExec;
import org.vast.process.ProcessInfo;
import org.vast.sensorML.AbstractProcessImpl;
import org.vast.sensorML.AggregateProcessImpl;
import org.vast.sensorML.LinkImpl;
import org.vast.sensorML.SMLUtils;
import org.vast.swe.SWEHelper;
import com.rits.cloning.Cloner;
import com.vaadin.annotations.JavaScript;
import com.vaadin.annotations.StyleSheet;
import com.vaadin.shared.ui.JavaScriptComponentState;
import com.vaadin.ui.AbstractJavaScriptComponent;
import com.vaadin.ui.JavaScriptFunction;
import com.vaadin.ui.UI;
import com.vaadin.ui.VerticalLayout;
import com.vaadin.ui.Window;
import elemental.json.JsonArray;
import net.opengis.OgcProperty;
import net.opengis.sensorml.v20.AbstractProcess;
import net.opengis.sensorml.v20.IOPropertyList;
import net.opengis.sensorml.v20.Link;
import net.opengis.swe.v20.AbstractSWEIdentifiable;
import net.opengis.swe.v20.DataArray;
import net.opengis.swe.v20.DataComponent;


@JavaScript({ "vaadin://js/jquery.min.js", "vaadin://js/lodash.min.js", "vaadin://js/backbone.min.js", "vaadin://js/joint.js", "vaadin://js/process_flow.js" })
@StyleSheet({ "vaadin://js/joint.css", "vaadin://js/process_flow.css" })
public class ProcessFlowDiagram extends AbstractJavaScriptComponent
{
    private static final long serialVersionUID = -592025593571688408L;
    private static final String SOURCE_NAME_PREFIX = "source";
    ArrayList<DiagramChangeListener> listeners = new ArrayList<>();
    AggregateProcessImpl processChain;
    

    public interface DiagramChangeListener
    {
        void onChange();
    }
    
    
    /*
     * Diagram state
     */
    public static class ProcessFlowState extends JavaScriptComponentState
    {
        private static final long serialVersionUID = 6791032489620399536L;
        public Map<String, ProcessBlock> dataSources = new HashMap<>();
        public Map<String, ProcessBlock> processBlocks = new HashMap<>();
        public Map<String, Connection> connections = new HashMap<>();
        public List<Port> inputs = new ArrayList<>();
        public List<Port> params = new ArrayList<>();
        public List<Port> outputs = new ArrayList<>();
    }
    
    
    /*
     * Diagram block drawing information
     */
    public static class ProcessBlock
    {
        public String id;
        public String name;
        public String type;
        public String uri;
        public String desc;
        public int x;
        public int y;
        public int w;
        public int h;
        
        /*
         * List of port objects
         * One entry is created per scalar component using the full path
         * as the port name (e.g. "input1/pos/lat")
         */
        public List<Port> inputs = new ArrayList<>();
        public List<Port> params = new ArrayList<>();
        public List<Port> outputs = new ArrayList<>();
    }
    
    
    /*
     * Metadata about a single port
     */
    public static class Port
    {
        public String path;
        public String type;
        public String value;
    }
    
    
    /*
     * Connection between two processes
     */
    public static class Connection
    {
        public String id;
        public String src;
        public String dest;
    }
    
    
    public ProcessFlowDiagram(AggregateProcessImpl processChain)
    {
        this.processChain = new Cloner().deepClone(processChain);
        setupCallbacks();
        updateState();        
    }
    
    
    protected void updateState()
    {
        // external i/o ports
        getState().inputs.clear();
        addPorts(processChain.getInputList(), getState().inputs);
        getState().params.clear();
        addPorts(processChain.getParameterList(), getState().params);
        getState().outputs.clear();
        addPorts(processChain.getOutputList(), getState().outputs);
        
        // sub-components
        getState().dataSources.clear();
        getState().processBlocks.clear();
        int ySrc = 20;
        int yProc = 20;
        for (OgcProperty<AbstractProcess> prop: processChain.getComponentList().getProperties())
        {
            AbstractProcessImpl process = (AbstractProcessImpl)prop.getValue();
            if (prop.getName().startsWith(SOURCE_NAME_PREFIX))
                addDataSource(prop.getName(), process, 20, ySrc += 150, 150, 120);
            else
                addProcess(prop.getName(), process, 400, yProc += 150, 150, 120);
        }
        
        // connections
        getState().connections.clear();
        for (Link link: processChain.getConnectionList())
        {
            Connection conn = new Connection();
            conn.id = link.getId();
            conn.src = link.getSource();
            conn.dest = link.getDestination();
            addConnection(conn);
        }
    }
    
    
    @SuppressWarnings("serial")
    protected void setupCallbacks()
    {
        addFunction("onChangeLink", new JavaScriptFunction() {
            @Override
            public void call(JsonArray args) {
                Connection conn = new Connection();
                conn.id = args.getString(0);
                conn.src = args.getString(1);
                conn.dest = args.getString(2);
                addConnection(conn);      // updates UI state
                upsertProcessChainLink(conn); // <-- persist to processChain
                notifyListeners();
            }
        });

        addFunction("onRemoveLink", new JavaScriptFunction() {
            @Override
            public void call(JsonArray args) {
                String id = args.getString(0);
                getState().connections.remove(id);
                removeProcessChainLink(id); // <-- mirror removal
                notifyListeners();
            }
        });


        addFunction("onChangeElement", new JavaScriptFunction() {
            @Override
            public void call(JsonArray args)
            {
                String name = args.getString(0);
                ProcessBlock b = getState().processBlocks.get(name);
                if (b != null)
                {
                    b.x = (int)args.getNumber(1);
                    b.y = (int)args.getNumber(2);
                    b.w = (int)args.getNumber(3);
                    b.h = (int)args.getNumber(4);
                    notifyListeners();
                }
            }
        });
        
        addFunction("onRemoveElement", new JavaScriptFunction() {
            @Override
            public void call(JsonArray args)
            {
                String name = args.getString(0);
                processChain.getComponentList().remove(name);
                updateState();
                notifyListeners();
            }            
        });

        addFunction("onContextMenu", new JavaScriptFunction() {
            @Override
            public void call(JsonArray args) {
                String action = args.getString(0);
                String blockName = args.getString(1);
                String portName = args.getString(2);

                // normalize action names coming from the UI
                switch (action) {
                    case "addInput":
                    case "exposeInput":
                    case "exposeAsInput":
                    case "Expose as input":
                        addExternalInput(blockName, portName);
                        break;

                    case "setInput":
                    case "setValue":
                    case "Set value":
                        setInputValues(blockName, portName);
                        break;

                    case "setParam":
                    case "setParameter":
                        setParamValues(blockName, portName);
                        break;

                    default:
                        // ignore unknowns to avoid NPEs
                        return;
                }
                notifyListeners();
            }
        });

    }

    protected void upsertProcessChainLink(Connection conn) {
        // replace if same id exists
        for (int i = 0; i < processChain.getConnectionList().size(); i++) {
            Link l = processChain.getConnectionList().get(i);
            if (conn.id != null && conn.id.equals(l.getId())) {
                l.setSource(conn.src);
                l.setDestination(conn.dest);
                return;
            }
        }
        // otherwise add a new link
        Link l = new LinkImpl();
        l.setId(conn.id != null ? conn.id : UUID.randomUUID().toString());
        l.setSource(conn.src);
        l.setDestination(conn.dest);
        processChain.getConnectionList().add(l);
    }

    protected void removeProcessChainLink(String id) {
        if (id == null) return;
        List<Link> links = processChain.getConnectionList();
        for (int i = links.size() - 1; i >= 0; i--) {
            Link l = links.get(i);
            if (id.equals(l.getId())) {
                links.remove(i);
            }
        }
    }



    protected void addExternalInput(String blockName, String portName) {
        for (Port p : getState().inputs) {
            if (p.path.equals(portName)) return; // already exposed
        }
        Port port = new Port();
        port.path = portName;
        getState().inputs.add(port);
    }



    protected void addExternalParam(String blockName, String portName)
    {
        
    }
    
    
    protected void addExternalOutput(String blockName, String portName)
    {
        
    }


    protected void setInputValues(String blockName, String portName) {
        AbstractProcess process = processChain.getComponent(blockName);
        DataComponent inputPort = process.getInputComponent(portName); // <-- FIXED
        editValues("Set Input", inputPort);
    }

    protected void setParamValues(String blockName, String portName) {
        AbstractProcess process = processChain.getComponent(blockName);
        DataComponent paramPort = process.getParameterComponent(portName);
        editValues("Set Parameter", paramPort);
    }

    protected void editValues(String title, DataComponent component) {
        Window popup = new Window(title);        // <-- dynamic title
        VerticalLayout content = new VerticalLayout();
        popup.setContent(content);
        popup.center();

        SWEParamForm form = new SWEParamForm(component);
        content.addComponent(form);
        getUI().addWindow(popup);
    }



    protected void addDataSource(ProcessBlock b)
    {
        getState().dataSources.put(b.name, b);
        notifyListeners();
    }
    
    
    protected void addDataSource(String name, AbstractProcess process, int x, int y, int w, int h)
    {
        try
        {
            // create new process block
            ProcessBlock newBlock = new ProcessBlock();
            newBlock.id = process.getUniqueIdentifier();
            newBlock.name = name;
            newBlock.type = process.getName();
            newBlock.desc = process.getDescription();
            newBlock.uri = StreamDataSource.INFO.getUri();
            newBlock.x = x;
            newBlock.y = y;
            newBlock.w = w;
            newBlock.h = h;
            
            // add i/o ports
            addPorts(process.getOutputList(), newBlock.outputs);
            
            // add block to shared UI state
            // diagram will update automatically
            addDataSource(newBlock);
        }
        catch (Exception e)
        {
            ((AdminUI)UI.getCurrent()).getOshLogger().error("Cannot add data source to diagram", e);
        }
    }
    
    
    protected void addNewDataSource(IDataProducer dataProducer) throws ProcessingException
    {
        // check that data source is not already in process chain
        for (ProcessBlock source: getState().dataSources.values())
        {
            if (source.id.equals(dataProducer.getUniqueIdentifier()))
                throw new ProcessingException(String.format("Data source '%s' was already added", dataProducer.getName()));
        }
        
        // compute new source name
        int srcNum = 0;
        while (getState().dataSources.containsKey(SOURCE_NAME_PREFIX+srcNum))
            srcNum++;
        String name = SOURCE_NAME_PREFIX + srcNum;
        
        int numSources = getState().dataSources.size();
        addDataSource(name, dataProducer.getCurrentDescription(), 20, 30+numSources*150, 150, 120);
    }
    
    
    protected void addProcess(ProcessBlock b)
    {
        getState().processBlocks.put(b.name, b);
        notifyListeners();
    }
    
    
    protected void addProcess(String name, IProcessExec process, int x, int y, int w, int h)
    {
        try
        {
            // create new process block
            ProcessBlock newBlock = new ProcessBlock();
            newBlock.id = UUID.randomUUID().toString();
            newBlock.name = name;
            newBlock.type = process.getProcessInfo().getName();
            newBlock.desc = process.getProcessInfo().getDescription();
            newBlock.uri = process.getProcessInfo().getUri();
            newBlock.x = x;
            newBlock.y = y;
            newBlock.w = w;
            newBlock.h = h;
            
            // add i/o ports            
            addPorts(process.getInputList(), newBlock.inputs);
            addPorts(process.getParameterList(), newBlock.params);
            addPorts(process.getOutputList(), newBlock.outputs);
            
            // add block to shared UI state
            // diagram will update automatically
            addProcess(newBlock);
        }
        catch (Exception e)
        {
            ((AdminUI)UI.getCurrent()).getOshLogger().error("Cannot add process to diagram", e);
        }
    }
    
    
    protected void addNewProcess(String name, ProcessInfo info) throws ProcessingException
    {
        // check that name is not already used
        if (getState().processBlocks.containsKey(name))
            throw new ProcessingException(String.format("Process with name '%s' already exists", name));
        
        try
        {
            int numBlocks = processChain.getNumComponents();
            IProcessExec execProcess = info.getImplementationClass().newInstance();
            AbstractProcess smlProcess = SMLUtils.wrapWithProcessDescription(execProcess);
            smlProcess.addExtension(new Rectangle(400, 30+numBlocks*150, 150, 120));
            processChain.addComponent(name, smlProcess);
            updateState();
        }
        catch (Exception e)
        {
            throw new ProcessingException(String.format("Cannot use process '%s'", info.getName()), e);
        }
    }
    
    
    protected void addPorts(IOPropertyList execPorts, List<Port> portList)
    {
        for (AbstractSWEIdentifiable port: execPorts)
        {
            DataIterator it = new DataIterator((DataComponent)port);
            while (it.hasNext())
            {
                DataComponent comp = it.next();
                Port diagPort = new Port();
                diagPort.path = SWEHelper.getComponentPath(comp);
                portList.add(diagPort);
                
                // don't allow links to array components
                if (comp instanceof DataArray)
                    it.skipChildren();
            }
        }
    }
    
    
    protected void addConnection(Connection conn)
    {
        getState().connections.put(conn.id, conn);
        notifyListeners();
    }
    
    
    public void addChangeListener(DiagramChangeListener listener)
    {
        this.listeners.add(listener);
    }
    
    
    protected void notifyListeners()
    {
        for (DiagramChangeListener listener: listeners)
            listener.onChange();
    }

    
    @Override
    protected ProcessFlowState getState()
    {
        return (ProcessFlowState)super.getState();
    }
}
