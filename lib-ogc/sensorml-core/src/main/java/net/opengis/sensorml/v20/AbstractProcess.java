/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package net.opengis.sensorml.v20;

import java.util.List;
import org.vast.ogc.om.IProcedure;
import org.vast.swe.SWEConstants;
import net.opengis.gml.v32.AbstractGeometry;
import net.opengis.gml.v32.Reference;
import net.opengis.swe.v20.AbstractSWEIdentifiable;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataStream;


/**
 * POJO class for XML type AbstractProcessType(@http://www.opengis.net/sensorml/2.0).
 *
 * This is a complex type.
 */
@SuppressWarnings("javadoc")
public interface AbstractProcess extends DescribedObject, IProcedure
{
    
    public default String getType()
    {
        // use definition or generate default type
        if (getDefinition() != null)
            return getDefinition();
        return SWEConstants.DEF_SYSTEM;
    }
    
    
    public default String getAssetType()
    {
        // use "AssetType" classifier if available
        for (var list: getClassificationList()) {
            for (var classifier: list.getClassifierList()) {
                if (SWEConstants.DEF_ASSET_TYPE.equals(classifier.getDefinition())) {
                    return classifier.getValue();
                }
            }
        }
        
        if (!(this instanceof AbstractPhysicalProcess))
            return SWEConstants.ASSET_TYPE_PROCESS;
        
        return null;
    }
    
    
    /**
     * Gets the typeOf property
     */
    public Reference getTypeOf();
    
    
    /**
     * Checks if typeOf is set
     */
    public boolean isSetTypeOf();
    
    
    /**
     * Sets the typeOf property
     */
    public void setTypeOf(Reference typeOf);
    
    
    /**
     * Gets the configuration property
     */
    public Settings getConfiguration();
    
    
    /**
     * Checks if configuration is set
     */
    public boolean isSetConfiguration();
    
    
    /**
     * Sets the configuration property
     */
    public void setConfiguration(Settings configuration);
    
    
    /**
     * Gets the featuresOfInterest property
     */
    public FeatureList getFeaturesOfInterest();
    
    
    /**
     * Checks if featuresOfInterest is set
     */
    public boolean isSetFeaturesOfInterest();
    
    
    /**
     * Sets the featuresOfInterest property
     */
    public void setFeaturesOfInterest(FeatureList featuresOfInterest);
    
    
    /**
     * Gets the list of input properties
     */
    public IOPropertyList getInputList();
    
    
    /**
     * Returns number of input properties
     */
    public int getNumInputs();
    
    
    /**
     * Gets the input property with the given name
     */
    public AbstractSWEIdentifiable getInput(String name);
    
    
    /**
     * Gets the data structure of the input with the given name
     */
    public DataComponent getInputComponent(String name);
    
    
    /**
     * Adds a new inputAsAbstractDataComponent property
     */
    public void addInput(String name, DataComponent input);
    
    
    /**
     * Adds a new inputAsObservableProperty property
     */
    public void addInput(String name, ObservableProperty input);
    
    
    /**
     * Adds a new inputAsDataStream property
     */
    public void addInput(String name, DataStream input);
    
    
    /**
     * Adds a new inputAsDataInterface property
     */
    public void addInput(String name, DataInterface input);
    
    
    /**
     * Gets the list of output properties
     */
    public IOPropertyList getOutputList();
    
    
    /**
     * Returns number of output properties
     */
    public int getNumOutputs();
    
    
    /**
     * Gets the output property with the given name
     */
    public AbstractSWEIdentifiable getOutput(String name);
    
    
    /**
     * Gets the data structure of the output with the given name
     */
    public DataComponent getOutputComponent(String name);
    
    
    /**
     * Adds a new outputAsAbstractDataComponent property
     */
    public void addOutput(String name, DataComponent output);
    
    
    /**
     * Adds a new outputAsObservableProperty property
     */
    public void addOutput(String name, ObservableProperty output);
    
    
    /**
     * Adds a new outputAsDataStream property
     */
    public void addOutput(String name, DataStream output);
    
    
    /**
     * Adds a new outputAsDataInterface property
     */
    public void addOutput(String name, DataInterface output);
    
    
    /**
     * Gets the list of parameter properties
     */
    public IOPropertyList getParameterList();
    
    
    /**
     * Returns number of parameter properties
     */
    public int getNumParameters();
    
    
    /**
     * Gets the parameter property with the given name
     */
    public AbstractSWEIdentifiable getParameter(String name);
    
    
    /**
     * Gets the data structure of the parameter with the given name
     */
    public DataComponent getParameterComponent(String name);
    
    
    /**
     * Adds a new parameterAsAbstractDataComponent property
     */
    public void addParameter(String name, DataComponent parameter);
    
    
    /**
     * Adds a new parameterAsObservableProperty property
     */
    public void addParameter(String name, ObservableProperty parameter);
    
    
    /**
     * Adds a new parameterAsDataStream property
     */
    public void addParameter(String name, DataStream parameter);
    
    
    /**
     * Adds a new parameterAsDataInterface property
     */
    public void addParameter(String name, DataInterface parameter);
        
    
    /**
     * Gets the list of modes properties
     */
    public List<AbstractModes> getModesList();
    
    
    /**
     * Returns number of modes properties
     */
    public int getNumModes();
    
    
    /**
     * Adds a new modes property
     */
    public void addModes(AbstractModes modes);
    
    
    /**
     * Gets the definition property
     */
    public String getDefinition();
    
    
    /**
     * Checks if definition is set
     */
    public boolean isSetDefinition();
    
    
    /**
     * Sets the definition property
     */
    public void setDefinition(String definition);
    
    
    public default AbstractGeometry getLocation()
    {
        return getGeometry();
    }
    
    
    public default boolean isSetLocation()
    {
        return isSetGeometry();
    }
    
    
    public default void setLocation(AbstractGeometry geom)
    {
        setGeometry(geom);
    }
}
