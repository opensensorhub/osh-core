/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2022 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import org.sensorhub.api.command.ICommandStreamInfo;
import org.sensorhub.api.data.IDataStreamInfo;
import org.sensorhub.impl.service.consys.obs.CustomObsFormat;
import org.sensorhub.impl.service.consys.resource.ResourceFormat;
import org.sensorhub.impl.service.consys.task.CustomCommandFormat;
import org.vast.cdm.common.DataStreamParser;
import org.vast.cdm.common.DataStreamWriter;
import org.vast.data.DataIterator;
import org.vast.data.JSONEncodingImpl;
import org.vast.data.TextEncodingImpl;
import org.vast.data.XMLEncodingImpl;
import org.vast.swe.IComponentFilter;
import org.vast.swe.SWEConstants;
import org.vast.swe.SWEHelper;
import org.vast.swe.fast.JsonDataParserGson;
import org.vast.swe.fast.JsonDataWriterGson;
import org.vast.swe.fast.TextDataParser;
import org.vast.swe.fast.TextDataWriter;
import org.vast.swe.fast.XmlDataParser;
import org.vast.swe.fast.XmlDataWriter;
import org.vast.swe.helper.RasterHelper;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import net.opengis.swe.v20.BinaryBlock;
import net.opengis.swe.v20.BinaryEncoding;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;
import net.opengis.swe.v20.DataRecord;
import net.opengis.swe.v20.JSONEncoding;
import net.opengis.swe.v20.TextEncoding;
import net.opengis.swe.v20.Vector;
import net.opengis.swe.v20.XMLEncoding;


public class SWECommonUtils
{
    public static final String NO_NAME = "noname";
    
    public static final Set<String> OM_COMPONENTS_DEF = ImmutableSet.of(
        SWEConstants.DEF_PHENOMENON_TIME,
        SWEConstants.DEF_SAMPLING_TIME,
        SWEConstants.DEF_FORECAST_TIME,
        SWEConstants.DEF_RUN_TIME
    );
    
    /*
     * Filter to skip properties already provided by O&M from result
     * e.g. time stamp and feature of interest ID
     */
    public static final IComponentFilter OM_COMPONENTS_FILTER = new IComponentFilter() {
        @Override
        public boolean accept(DataComponent comp)
        {
            var def = comp.getDefinition();
            if (comp.getParent() == null || OM_COMPONENTS_DEF.contains(def))
                return false;
            else
                return true;
        }
    };
    
    
    /*
     * Choose proper encoding for the selected SWE Common sub format
     * @throws InvalidRequestException if sub format is not supported
     */
    public static DataEncoding getEncoding(DataComponent dataStruct, DataEncoding defaultEncoding, ResourceFormat format) throws InvalidRequestException
    {
        // find correct encoding depending on desired format and native encoding
        if (defaultEncoding instanceof BinaryEncoding)
        {
            if (format.equals(ResourceFormat.SWE_BINARY))
            {
                return defaultEncoding;
            }
            else if (ResourceFormat.allowNonBinaryFormat(defaultEncoding))
            {
                if (format.isOneOf(ResourceFormat.SWE_JSON, ResourceFormat.JSON))
                {
                    return new JSONEncodingImpl();
                }
                else if (format.isOneOf(ResourceFormat.SWE_TEXT, ResourceFormat.TEXT_PLAIN, ResourceFormat.TEXT_CSV))
                {
                    return new TextEncodingImpl();
                }
                else if (format.isOneOf(ResourceFormat.SWE_XML, ResourceFormat.TEXT_XML))
                {
                    return new XMLEncodingImpl();
                }
            }
        }
        else
        {
            if (format.isOneOf(ResourceFormat.SWE_JSON, ResourceFormat.JSON))
            {
                return new JSONEncodingImpl();
            }
            else if (format.isOneOf(ResourceFormat.SWE_TEXT, ResourceFormat.TEXT_PLAIN, ResourceFormat.TEXT_CSV))
            {
                return defaultEncoding instanceof TextEncoding ? defaultEncoding : new TextEncodingImpl();
            } 
            else if (format.isOneOf(ResourceFormat.SWE_XML, ResourceFormat.TEXT_XML))
            {
                return new XMLEncodingImpl();
            }
            else if (format.equals(ResourceFormat.SWE_BINARY))
            {
                return SWEHelper.getDefaultBinaryEncoding(dataStruct);
            }
        }
        
        throw ServiceErrors.unsupportedFormat(format);
    }
    
    
    /*
     * Create proper stream writer for the selected SWE Common sub format
     * @throws InvalidRequestException if sub format is not supported
     */
    public static DataStreamWriter getWriter(DataComponent dataStruct, DataEncoding defaultEncoding, ResourceFormat format) throws InvalidRequestException
    {
        DataStreamWriter dataWriter = null;
        var sweEncoding = getEncoding(dataStruct, defaultEncoding, format);
        
        if (sweEncoding instanceof JSONEncoding)
        {
            dataWriter = new JsonDataWriterGson();
        }
        else if (sweEncoding instanceof TextEncoding)
        {
            dataWriter = new TextDataWriter();
            dataWriter.setDataEncoding(sweEncoding);
        } 
        else if (sweEncoding instanceof XMLEncoding)
        {
            dataWriter = new XmlDataWriter();
            dataWriter.setDataEncoding(sweEncoding);
        }
        else if (sweEncoding instanceof BinaryEncoding)
        {
            dataWriter = SWEHelper.createDataWriter(sweEncoding);
        }
        else
            throw ServiceErrors.unsupportedFormat(format);
        
        dataWriter.setDataComponents(dataStruct);
        return dataWriter;
    }
    
    
    /*
     * Create proper stream parser for the selected SWE Common sub format
     * @throws InvalidRequestException if sub format is not supported
     */
    public static DataStreamParser getParser(DataComponent dataStruct, DataEncoding defaultEncoding, ResourceFormat format) throws InvalidRequestException
    {
        DataStreamParser dataParser = null;
        var sweEncoding = getEncoding(dataStruct, defaultEncoding, format);
        
        if (sweEncoding instanceof JSONEncoding)
        {
            dataParser = new JsonDataParserGson();
        }
        else if (sweEncoding instanceof TextEncoding)
        {
            dataParser = new TextDataParser();
            dataParser.setDataEncoding(sweEncoding);
        } 
        else if (sweEncoding instanceof XMLEncoding)
        {
            dataParser = new XmlDataParser();
            dataParser.setDataEncoding(sweEncoding);
        }
        else if (sweEncoding instanceof BinaryEncoding)
        {
            dataParser = SWEHelper.createDataParser(sweEncoding);
        }
        else
            throw ServiceErrors.unsupportedFormat(format);
        
        dataParser.setDataComponents(dataStruct);
        return dataParser;
    }
    
    
    /*
     * Get list of format available for a given datastream
     */
    public static Collection<String> getAvailableFormats(IDataStreamInfo dsInfo, Map<String, CustomObsFormat> customFormats)
    {
        var formatList = new ArrayList<String>();
        
        formatList.add(ResourceFormat.OM_JSON.getMimeType());
        
        if (ResourceFormat.allowNonBinaryFormat(dsInfo.getRecordEncoding()))
        {
            formatList.add(ResourceFormat.SWE_JSON.getMimeType());
            formatList.add(ResourceFormat.SWE_TEXT.getMimeType());
            formatList.add(ResourceFormat.SWE_XML.getMimeType());
        }
        
        formatList.add(ResourceFormat.SWE_BINARY.getMimeType());
        
        // also list compatible custom formats
        for (var formatEntry: customFormats.entrySet())
        {
            if (formatEntry.getValue().isCompatible(dsInfo))
                formatList.add(formatEntry.getKey());
        }
        
        return formatList;
    }
    
    
    /*
     * Get list of format available for a given command stream
     */
    public static Collection<String> getAvailableFormats(ICommandStreamInfo csInfo, Map<String, CustomCommandFormat> customFormats)
    {
        var formatList = new ArrayList<String>();
        
        formatList.add(ResourceFormat.JSON.getMimeType());
        
        if (ResourceFormat.allowNonBinaryFormat(csInfo.getRecordEncoding()))
        {
            formatList.add(ResourceFormat.SWE_JSON.getMimeType());
            formatList.add(ResourceFormat.SWE_TEXT.getMimeType());
            formatList.add(ResourceFormat.SWE_XML.getMimeType());
        }
        
        formatList.add(ResourceFormat.SWE_BINARY.getMimeType());
        
        // also list compatible custom formats
        for (var formatEntry: customFormats.entrySet())
        {
            if (formatEntry.getValue().isCompatible(csInfo))
                formatList.add(formatEntry.getKey());
        }
        
        return formatList;
    }
    
    
    public static Iterable<DataComponent> getProperties(DataComponent dataStruct)
    {
        return Iterables.filter(new DataIterator(dataStruct), comp -> {
            var def = comp.getDefinition();
            
            // skip vector coordinates
            if (comp.getParent() instanceof Vector)
                return false;
            
            // skip data records
            if (comp instanceof DataRecord)
                return false;
            
            // skip well known fields
            if (SWEConstants.DEF_SAMPLING_TIME.equals(def) ||
                SWEConstants.DEF_PHENOMENON_TIME.equals(def) ||
                SWEConstants.DEF_FORECAST_TIME.equals(def) ||
                SWEConstants.DEF_SYSTEM_ID.equals(def) ||
                SWEConstants.DEF_ARRAY_SIZE.equals(def) ||
                SWEConstants.DEF_NUM_POINTS.equals(def) ||
                SWEConstants.DEF_NUM_SAMPLES.equals(def) ||
                SWEConstants.DEF_NUM_ROWS.equals(def) ||
                RasterHelper.DEF_RASTER_WIDTH.equals(def) ||
                RasterHelper.DEF_RASTER_HEIGHT.equals(def))
                return false;
            
            // skip if no metadata was set
            if (Strings.isNullOrEmpty(def) &&
                Strings.isNullOrEmpty(comp.getLabel()) &&
                Strings.isNullOrEmpty(comp.getDescription()))
                return false;
            
            return true;
        });
    }
    
    
    public static boolean allowNonBinaryFormat(DataComponent dataStruct, DataEncoding encoding)
    {
        if (encoding instanceof BinaryEncoding)
        {
            var enc = (BinaryEncoding)encoding;
            for (var member: enc.getMemberList())
            {
                if (member instanceof BinaryBlock)
                    return false;
            }
        }
        
        return true;
    }

}
