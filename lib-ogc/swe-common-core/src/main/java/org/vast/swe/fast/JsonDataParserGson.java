/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.

Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.

******************************* END LICENSE BLOCK ***************************/

package org.vast.swe.fast;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import org.vast.data.AbstractDataBlock;
import org.vast.data.DataBlockDouble;
import org.vast.data.DataBlockInt;
import org.vast.data.DataBlockList;
import org.vast.data.DataBlockMixed;
import org.vast.util.Asserts;
import org.vast.util.DateTimeFormat;
import org.vast.util.ReaderException;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.MalformedJsonException;
import gnu.trove.list.TDoubleList;
import gnu.trove.list.array.TDoubleArrayList;
import net.opengis.swe.v20.Boolean;
import net.opengis.swe.v20.Category;
import net.opengis.swe.v20.Count;
import net.opengis.swe.v20.DataArray;
import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataChoice;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataRecord;
import net.opengis.swe.v20.GeometryData;
import net.opengis.swe.v20.GeometryData.GeomType;
import net.opengis.swe.v20.Quantity;
import net.opengis.swe.v20.RangeComponent;
import net.opengis.swe.v20.Text;
import net.opengis.swe.v20.Time;
import net.opengis.swe.v20.Vector;


/**
 * <p>
 * New implementation of JSON data parser with better efficiency since the
 * read tree is pre-computed during init instead of being re-evaluated
 * while iterating through the component tree.
 * </p><p>
 * This particular implementation is based on Gson JsonReader.
 * </p>
 *
 * @author Alex Robin
 * @since Jan 26, 2021
 */
public class JsonDataParserGson extends AbstractDataParser
{
    static final String DOUBLE_VALUE_ERROR = "Expected decimal value or one of ['NaN', '-INF', '+INF']";
    static final String DEFAULT_INDENT = "  ";

    protected JsonReader reader;
    protected boolean multipleRecords;
    protected Map<String, IntegerReader> countReaders = new HashMap<>();
    protected boolean hasJsonArrayWrapper = false;


    protected interface JsonAtomReader
    {
        String getEltName();
    }


    protected abstract class ValueReader extends BaseProcessor implements JsonAtomReader
    {
        String eltName;

        public abstract void readValue(DataBlock data, int index) throws IOException;

        @Override
        public int process(DataBlock data, int index) throws IOException
        {
            try
            {
                if (enabled)
                    readValue(data, index);
                return ++index;
            }
            catch (NumberFormatException | ReaderException e)
            {
                throw new ReaderException(e.getMessage() + " at " + reader.getPath());
            }
        }

        @Override
        public String getEltName()
        {
            return eltName;
        }
    }


    protected class BooleanReader extends ValueReader
    {
        public BooleanReader(String eltName)
        {
            this.eltName = eltName;
        }

        @Override
        public void readValue(DataBlock data, int index) throws IOException
        {
            var val = reader.nextBoolean();
            data.setBooleanValue(index, val);
        }
    }


    protected class IntegerReader extends ValueReader
    {
        int val;

        public IntegerReader(String eltName)
        {
            this.eltName = eltName;
        }

        @Override
        public void readValue(DataBlock data, int index) throws IOException
        {
            val = reader.nextInt();
            data.setIntValue(index, val);
        }
    }


    protected class DoubleReader extends ValueReader
    {
        public DoubleReader(String eltName)
        {
            this.eltName = eltName;
        }

        @Override
        public void readValue(DataBlock data, int index) throws IOException
        {
            var token = reader.peek();
            double val = 0.0;
            
            switch (token)
            {
                case NUMBER:
                    val = reader.nextDouble();
                    break;
                    
                // handle cases of special values
                case STRING:
                    var str = reader.nextString();
                    if ("NaN".equals(str))
                        val = Double.NaN;
                    else if ("+INF".equals(str) || "INF".equals(str))
                        val = Double.POSITIVE_INFINITY;
                    else if ("-INF".equals(str))
                        val = Double.NEGATIVE_INFINITY;
                    else
                        throw new NumberFormatException(DOUBLE_VALUE_ERROR);
                    break;
                    
                default:
                    throw new ReaderException(DOUBLE_VALUE_ERROR + " but was " + token + " at " + reader.getPath());
            }
                        
            data.setDoubleValue(index, val);
        }
    }


    protected class IsoDateTimeReader extends ValueReader
    {
        DateTimeFormat timeFormat = new DateTimeFormat();

        public IsoDateTimeReader(String eltName)
        {
            this.eltName = eltName;
        }

        @Override
        public void readValue(DataBlock data, int index) throws IOException
        {
            double val = Double.NaN;
            if (reader.peek() != JsonToken.NULL)
            {
                var str = reader.nextString();
                
                if (str == null || "NaN".equals(str))
                    val = Double.NaN;
                else if ("+INF".equals(str) || "INF".equals(str))
                    val = Double.POSITIVE_INFINITY;
                else if ("-INF".equals(str))
                    val = Double.NEGATIVE_INFINITY;
                else
                {
                    try { val = timeFormat.parseIso(str); }
                    catch (Exception e) { throw new ReaderException(e.getMessage()); }
                }
            }
            else
                reader.nextNull();
            
            data.setDoubleValue(index, val);
        }
    }


    protected class StringReader extends ValueReader
    {
        static final int BUF_SIZE_INCREMENT = 64;
        char[] buf = new char[2*BUF_SIZE_INCREMENT];
        int pos;
        
        public StringReader(String eltName)
        {
            this.eltName = eltName;
        }

        @Override
        public void readValue(DataBlock data, int index) throws IOException
        {
            String str = null;
            if (reader.peek() == JsonToken.NULL) {
                reader.nextNull();
            } else {
                str = reader.nextString();
            }
            data.setStringValue(index, str);
        }
    }


    protected class RangeReader extends RecordProcessor implements JsonAtomReader
    {
        String eltName;

        public RangeReader(String eltName)
        {
            this.eltName = eltName;
        }

        @Override
        public int process(DataBlock data, int index) throws IOException
        {
            reader.beginArray();
            fieldProcessors.get(0).process(data, index++);
            fieldProcessors.get(1).process(data, index++);
            reader.endArray();
            return index;
        }

        @Override
        public String getEltName()
        {
            return eltName;
        }
    }


    protected class RecordReader extends RecordProcessor implements JsonAtomReader
    {
        String eltName;

        public RecordReader(String eltName)
        {
            this.eltName = eltName;
        }

        @Override
        public int process(DataBlock data, int index) throws IOException
        {
            reader.beginObject();
            
            for (AtomProcessor p: fieldProcessors)
            {
                if (p.isEnabled())
                {
                    var expectedName = ((JsonAtomReader)p).getEltName();
                    var actualName = reader.nextName();
                    if (!actualName.equals(expectedName))
                        throw new ReaderException("Expected field '" + expectedName + "' but was '" + actualName + "'");
                }
                
                index = p.process(data, index);
            }

            reader.endObject();
            
            return index;
        }

        @Override
        public String getEltName()
        {
            return eltName;
        }
    }


    protected class ArrayReader extends ArrayProcessor implements JsonAtomReader
    {
        String eltName;
        
        public ArrayReader(String eltName)
        {
            this.eltName = eltName;
        }

        @Override
        public int process(DataBlock data, int index) throws IOException
        {
            // resize array if var size
            int arraySize = getArraySize();
            if (varSizeArray != null)
                updateArraySize(varSizeArray, arraySize);
            
            reader.beginArray();
            
            // case of array with variable size items
            // e.g. item is itself a variable size array or a choice
            if (varSizeArray != null && varSizeArray.getData() instanceof DataBlockList)
            {
                var arrayData = (DataBlockList)varSizeArray.getData();
                var globalIdx = index;
                for (int i = 0; i < arraySize; i++)
                {
                    var itemData = arrayData.get(i);
                    globalIdx += eltProcessor.process(itemData, 0);
                }
                index = globalIdx;
                data.updateAtomCount();
            }
            else
            {
                for (int i = 0; i < arraySize; i++)
                    index = eltProcessor.process(data, index);
            }
            
            reader.endArray();
            
            return index;
        }

        @Override
        public String getEltName()
        {
            return eltName;
        }
    }


    protected class ChoiceReader extends ChoiceProcessor implements JsonAtomReader
    {
        String eltName;
        DataChoice choice;
        Map<String, Integer> itemIndexes = new HashMap<>();

        public ChoiceReader(DataChoice choice)
        {
            this.eltName = choice.getName();
            this.choice = choice;
            
            int i = 0;
            for (DataComponent item: choice.getItemList())
                itemIndexes.put(item.getName(), i++);
        }

        @Override
        public int process(DataBlock data, int index) throws IOException
        {
            reader.beginObject();
            
            var itemName = reader.nextName();
            var selectedIndex = itemIndexes.get(itemName);
            if (selectedIndex == null)
                throw new ReaderException(INVALID_CHOICE_MSG + itemName + " at " + reader.getPath());
            
            // set selected choice index and corresponding datablock
            data.setIntValue(index++, selectedIndex);
            var selectedData = choice.getComponent(selectedIndex).createDataBlock();
            ((DataBlockMixed)data).setBlock(1, (AbstractDataBlock)selectedData);
            
            // delegate to selected item processor
            index = super.process(data, index, selectedIndex);

            reader.endObject();

            return index;
        }

        @Override
        public String getEltName()
        {
            return eltName;
        }
    }


    protected class GeometryReader extends ChoiceProcessor implements JsonAtomReader
    {
        String eltName;
        GeometryData geom;
        GeomType geomType;
        boolean hasCoords;

        public GeometryReader(GeometryData geom)
        {
            this.geom = Asserts.checkNotNull(geom, GeometryData.class);
            this.eltName = geom.getName();
        }

        @Override
        public int process(DataBlock data, int index) throws IOException
        {
            var geomPath = reader.getPath();
            
            // get geom datablock
            var geomData = geom.getData();
            int numDims = geom.getNumDims();
            
            reader.beginObject();
            
            while (reader.hasNext())
            {
                String name = reader.nextName();
                
                if ("type".equals(name))
                {
                    var type = reader.nextString();
                    
                    try
                    {
                        geomType = GeomType.valueOf(type);
                        var selectedIndex = geomType.ordinal();
                        
                        // set selected choice index and corresponding datablock
                        data.setIntValue(index++, selectedIndex);
                        var selectedData = geom.getComponent(selectedIndex).createDataBlock();
                        ((DataBlockMixed)geomData).setBlock(1, (AbstractDataBlock)selectedData);
                    }
                    catch (IllegalArgumentException e)
                    {
                        throw new ReaderException("Unsupported geometry type: " + type + " at " + reader.getPath());
                    }
                }
                else if ("coordinates".equals(name))
                {
                    hasCoords = true;
                    int depth = 0;
                    int lvl = -1;
                    int numPoints = 0;
                    int numRings = 1;
                    TDoubleList coordsList = null;
                    
                    do
                    {
                        var next = reader.peek();
                        
                        if (next == JsonToken.BEGIN_ARRAY)
                        {
                            reader.beginArray();
                            lvl++;
                        }
                        else if (next == JsonToken.END_ARRAY)
                        {
                            reader.endArray();
                            
                            if (lvl == depth-2)
                            {
                                index ++;
                                numRings++;
                            }
                            
                            // add coordinates to datablock
                            if (depth == 0 || lvl == depth-1)
                            {
                                if (depth == 0) // point
                                {
                                    var pointCoords = ((DataBlockMixed)geomData).getUnderlyingObject()[1];
                                    ((DataBlockDouble)pointCoords).setUnderlyingObject(coordsList.toArray());
                                    index += coordsList.size();
                                }
                                
                                else if (depth == 1) // linestring
                                {
                                    geomData.setIntValue(1, numPoints);
                                    
                                    var lineData = ((DataBlockMixed)geomData).getUnderlyingObject()[1];
                                    var lineCoords = ((DataBlockMixed)lineData).getUnderlyingObject()[1];
                                    ((DataBlockDouble)lineCoords).setUnderlyingObject(coordsList.toArray());
                                    
                                    index += coordsList.size() + 1;
                                    numPoints = 0;
                                }
                                
                                else if (depth == 2) // polygon
                                {
                                    geomData.setIntValue(1, numRings);
                                    
                                    var polyData = ((DataBlockMixed)geomData).getUnderlyingObject()[1];
                                    var ringListData = ((DataBlockMixed)polyData).getUnderlyingObject()[1];
                                    
                                    var ringCoords = new DataBlockDouble(0);
                                    ringCoords.setUnderlyingObject(coordsList.toArray());
                                    var ringData = new DataBlockMixed(new DataBlockInt(1), ringCoords);
                                    ringData.setIntValue(0, numPoints);
                                    ((DataBlockList)ringListData).add(ringData);
                                    
                                    index += coordsList.size() + 1;
                                    numPoints = 0;
                                }
                            }
                            
                            lvl--;
                        }
                        else
                        {
                            depth = lvl;
                            if (coordsList == null)
                                coordsList = new TDoubleArrayList();
                            
                            int dims = 0;
                            while (reader.hasNext())
                            {
                                if (reader.peek() != JsonToken.NUMBER)
                                    throw new ReaderException("Invalid coordinate value at " + reader.getPath());
                                coordsList.add(reader.nextDouble());
                                dims++;
                            }
                            
                            if (dims != numDims)
                                throw new ReaderException("Read " + dims + " coordinates but expected " + numDims + " at " + reader.getPath());
                            numPoints++;
                        }
                    }
                    while (lvl >= 0);
                }
                else
                    reader.skipValue();
            }

            reader.endObject();
            
            if (geomType == null)
                throw new ReaderException("Missing geometry type at " + geomPath);
            
            if (!hasCoords)
                throw new ReaderException("Missing geometry coordinates at " + geomPath);
            
            data.updateAtomCount();
            return index;
        }

        @Override
        public String getEltName()
        {
            return eltName;
        }
    }
    
    
    public JsonDataParserGson()
    {
    }
    
    
    public JsonDataParserGson(JsonReader reader)
    {
        this.reader = reader;
    }


    @Override
    protected void init() throws IOException
    {
        if (hasJsonArrayWrapper)
            reader.beginArray();
    }


    @Override
    public void setInput(InputStream is) throws IOException
    {
        this.reader = new JsonReader(new InputStreamReader(is));
    }
    
    
    public void setHasArrayWrapper()
    {
        this.hasJsonArrayWrapper = true;
    }
    
    
    @Override
    public DataBlock parseNextBlock() throws IOException
    {
        try
        {
            return super.parseNextBlock();
        }
        catch (MalformedJsonException e)
        {
            // Fix error message advising switch to lenient mode
            var msg = e.getMessage().replaceAll("Use JsonReader.*malformed", "Malformed");
            throw new ReaderException(msg);
        }
        catch (IllegalStateException e)
        {
            throw new ReaderException(e);
        }
    }


    @Override
    public void close() throws IOException
    {
        if (reader != null)
            reader.close();
    }


    @Override
    public void reset()
    {
        super.reset();
    }


    @Override
    public void visit(Boolean comp)
    {
        addToProcessorTree(new BooleanReader(comp.getName()));
    }


    @Override
    public void visit(Count comp)
    {
        IntegerReader Reader = new IntegerReader(comp.getName());
        if (comp.isSetId())
            countReaders.put(comp.getId(), Reader);
        addToProcessorTree(Reader);
    }


    @Override
    public void visit(Quantity comp)
    {
        addToProcessorTree(new DoubleReader(comp.getName()));
    }


    @Override
    public void visit(Time comp)
    {
        if (!comp.isIsoTime())
            addToProcessorTree(new DoubleReader(comp.getName()));
        else
            addToProcessorTree(new IsoDateTimeReader(comp.getName()));
    }


    @Override
    public void visit(Category comp)
    {
        addToProcessorTree(new StringReader(comp.getName()));
    }


    @Override
    public void visit(Text comp)
    {
        addToProcessorTree(new StringReader(comp.getName()));
    }


    @Override
    public void visit(GeometryData geom)
    {
        addToProcessorTree(new GeometryReader(geom));
        hasVarSizeArray = true;
        processorStack.pop();
    }
    
    
    @Override
    protected AtomProcessor getRangeProcessor(RangeComponent range)
    {
        return new RangeReader(range.getName());
    }


    @Override
    protected RecordProcessor getRecordProcessor(DataRecord record)
    {
        return new RecordReader(record.getName());
    }


    @Override
    protected RecordProcessor getVectorProcessor(Vector vect)
    {
        return new RecordReader(vect.getName());
    }


    @Override
    protected ChoiceProcessor getChoiceProcessor(DataChoice choice)
    {
        return new ChoiceReader(choice);
    }
    
    
    @Override
    protected ArrayProcessor getArrayProcessor(DataArray array)
    {
        return new ArrayReader(array.getName());
    }
    
    
    @Override
    protected ImplicitSizeProcessor getImplicitSizeProcessor(DataArray array)
    {
        throw new IllegalStateException("Implicit size not supported by JSON parser");
    }
    
    
    @Override
    protected ArraySizeSupplier getArraySizeSupplier(String refId)
    {
        IntegerReader sizeReader = countReaders.get(refId);
        return () -> sizeReader.val;
    }


    @Override
    protected boolean moreData() throws IOException
    {
        return reader.peek() != JsonToken.END_DOCUMENT && reader.hasNext();
    }
}
