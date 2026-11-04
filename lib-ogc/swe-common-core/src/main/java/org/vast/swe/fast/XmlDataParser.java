/***************************** BEGIN LICENSE BLOCK ***************************

 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2025 Botts Innovative Research, Inc. All Rights Reserved.

 ******************************* END LICENSE BLOCK ***************************/
package org.vast.swe.fast;

import com.google.gson.stream.MalformedJsonException;
import gnu.trove.list.TDoubleList;
import gnu.trove.list.array.TDoubleArrayList;
import net.opengis.swe.v20.Boolean;
import net.opengis.swe.v20.*;
import org.codehaus.stax2.XMLInputFactory2;
import org.codehaus.stax2.XMLStreamReader2;
import org.vast.data.*;
import org.vast.util.Asserts;
import org.vast.util.DateTimeFormat;
import org.vast.util.ReaderException;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public class XmlDataParser extends AbstractDataParser {

    protected XMLStreamReader2 xmlStreamReader;

    protected boolean multipleRecords;

    protected Map<String, IntegerReader> countReaders = new HashMap<>();

    protected interface XmlAtomReader {
        String getEltName();
    }


    protected abstract class ValueReader extends BaseProcessor implements XmlAtomReader {
        String eltName;

        public abstract void readValue(DataBlock data, int index) throws IOException;

        @Override
        public int process(DataBlock data, int index) throws IOException {
            try {
                if (enabled)
                    readValue(data, index);
                return ++index;
            } catch (NumberFormatException | ReaderException e) {
                throw new ReaderException(e.getMessage() + " at " + xmlStreamReader.getLocation());
            }
        }

        @Override
        public String getEltName() {
            return eltName;
        }
    }


    protected class BooleanReader extends ValueReader {
        boolean val;

        public BooleanReader(String eltName) {
            this.eltName = eltName;
        }

        @Override
        public void readValue(DataBlock data, int index) throws IOException {
            try {
                val = xmlStreamReader.getElementAsBoolean();
                data.setBooleanValue(index, val);
            } catch (XMLStreamException e) {
                throw new IOException(e);
            }
        }
    }


    protected class IntegerReader extends ValueReader {
        int val;

        public IntegerReader(String eltName) {
            this.eltName = eltName;
        }

        @Override
        public void readValue(DataBlock data, int index) throws IOException {
            try {
                val = xmlStreamReader.getElementAsInt();
                data.setIntValue(index, val);
            } catch (XMLStreamException e) {
                throw new IOException(e);
            }
        }
    }


    protected class DoubleReader extends ValueReader {
        double val = 0.0;

        public DoubleReader(String eltName) {
            this.eltName = eltName;
        }

        @Override
        public void readValue(DataBlock data, int index) throws IOException {
            try {
                val = xmlStreamReader.getElementAsDouble();
                data.setDoubleValue(index, val);
            } catch (XMLStreamException e) {
                throw new IOException(e);
            }
        }
    }


    protected class IsoDateTimeReader extends ValueReader {
        DateTimeFormat timeFormat = new DateTimeFormat();

        public IsoDateTimeReader(String eltName) {
            this.eltName = eltName;
        }

        @Override
        public void readValue(DataBlock data, int index) throws IOException {
            try {
                var str = xmlStreamReader.getElementText();
                double val;
                if ("NaN".equals(str))
                    val = Double.NaN;
                else if ("+INF".equals(str) || "INF".equals(str))
                    val = Double.POSITIVE_INFINITY;
                else if ("-INF".equals(str))
                    val = Double.NEGATIVE_INFINITY;
                else {
                    try {
                        val = timeFormat.parseIso(str);
                    } catch (Exception e) {
                        throw new ReaderException(e.getMessage());
                    }
                }

                data.setDoubleValue(index, val);

            } catch (XMLStreamException e) {
                throw new IOException(e);
            }
        }
    }

    protected class StringReader extends ValueReader {
        static final int BUF_SIZE_INCREMENT = 64;
        char[] buf = new char[2 * BUF_SIZE_INCREMENT];
        int pos;

        public StringReader(String eltName) {
            this.eltName = eltName;
        }

        @Override
        public void readValue(DataBlock data, int index) throws IOException {
            try {
                var val = xmlStreamReader.getElementText();
                data.setStringValue(index, val);
            } catch (XMLStreamException e) {
                throw new IOException(e);
            }
        }
    }


    protected class RangeReader extends DataBlockProcessor.RecordProcessor implements XmlAtomReader {
        String eltName;

        public RangeReader(String eltName) {
            this.eltName = eltName;
        }

        @Override
        public int process(DataBlock data, int index) throws IOException {
            try {
                xmlStreamReader.next();
                fieldProcessors.get(0).process(data, index++);
                fieldProcessors.get(1).process(data, index++);
                xmlStreamReader.next();
            } catch (XMLStreamException e) {
                throw new IOException(e);
            }
            return index;
        }

        @Override
        public String getEltName() {
            return eltName;
        }
    }


    protected class RecordReader extends DataBlockProcessor.RecordProcessor implements XmlAtomReader {
        String eltName;

        public RecordReader(String eltName) {
            this.eltName = eltName;
        }

        @Override
        public int process(DataBlock data, int index) throws IOException {
            try {

                for (DataBlockProcessor.AtomProcessor p : fieldProcessors) {
                    if (p.isEnabled()) {
                        var expectedName = ((XmlAtomReader) p).getEltName();
                        String actualName;
                        do {
                            xmlStreamReader.next();
                            actualName = xmlStreamReader.getLocalName();
                        } while (!actualName.equals(expectedName) && xmlStreamReader.hasNext());
                        if (!actualName.equals(expectedName))
                            throw new ReaderException("Expected field '" + expectedName + "' but was '" + actualName + "'");
                    }

                    index = p.process(data, index);
                }

            } catch (XMLStreamException e) {
                throw new IOException(e);
            }

            return index;
        }

        @Override
        public String getEltName() {
            return eltName;
        }
    }


    protected class ArrayReader extends DataBlockProcessor.ArrayProcessor implements XmlAtomReader {
        String eltName;

        public ArrayReader(String eltName) {
            this.eltName = eltName;
        }

        @Override
        public int process(DataBlock data, int index) throws IOException {
            try {
                // resize array if var size
                int arraySize = getArraySize();
                if (varSizeArray != null)
                    updateArraySize(varSizeArray, arraySize);

                xmlStreamReader.next();

                // case of array with variable size items
                // e.g. item is itself a variable size array or a choice
                if (varSizeArray != null && varSizeArray.getData() instanceof DataBlockList arrayData) {
                    var globalIdx = index;
                    for (int i = 0; i < arraySize; i++) {
                        var itemData = arrayData.get(i);
                        globalIdx += eltProcessor.process(itemData, 0);
                    }
                    index = globalIdx;
                    data.updateAtomCount();
                } else {
                    for (int i = 0; i < arraySize; i++)
                        index = eltProcessor.process(data, index);
                }

                xmlStreamReader.next();
            } catch (XMLStreamException e) {
                throw new IOException(e);
            }

            return index;
        }

        @Override
        public String getEltName() {
            return eltName;
        }
    }


    protected class ChoiceReader extends DataBlockProcessor.ChoiceProcessor implements XmlAtomReader {
        String eltName;
        DataChoice choice;
        Map<String, Integer> itemIndexes = new HashMap<>();

        public ChoiceReader(DataChoice choice) {
            this.eltName = choice.getName();
            this.choice = choice;

            int i = 0;
            for (DataComponent item : choice.getItemList())
                itemIndexes.put(item.getName(), i++);

            try {
                xmlStreamReader.next();
            } catch (XMLStreamException ignored) {

            }
        }

        @Override
        public int process(DataBlock data, int index) throws IOException {
            try {
                xmlStreamReader.next();

                var itemName = xmlStreamReader.getLocalName();
                var selectedIndex = itemIndexes.get(itemName);
                if (selectedIndex == null)
                    throw new ReaderException(INVALID_CHOICE_MSG + itemName + " at " + xmlStreamReader.getLocationInfo());

                // set selected choice index and corresponding datablock
                data.setIntValue(index++, selectedIndex);
                var selectedData = choice.getComponent(selectedIndex).createDataBlock();
                ((DataBlockMixed) data).setBlock(1, (AbstractDataBlock) selectedData);

                // delegate to selected item processor
                index = super.process(data, index, selectedIndex);

            } catch (XMLStreamException e) {
                throw new IOException(e);
            }

            return index;
        }

        @Override
        public String getEltName() {
            return eltName;
        }
    }

    protected class GeometryReader extends DataBlockProcessor.ChoiceProcessor implements XmlAtomReader
    {
        String eltName;
        GeometryData geom;
        GeometryData.GeomType geomType;
        boolean hasCoords;

        public GeometryReader(GeometryData geom)
        {
            this.geom = Asserts.checkNotNull(geom, GeometryData.class);
            this.eltName = geom.getName();
        }

        @Override
        public int process(DataBlock data, int index) throws IOException
        {
            var geomPath = xmlStreamReader.getLocalName();

            // get geom datablock
            var geomData = geom.getData();
            int numDims = geom.getNumDims();

            try {

                while (xmlStreamReader.hasNext()) {
                    String name = xmlStreamReader.getLocalName();

                    if ("type".equals(name)) {

                        var type = xmlStreamReader.getElementText();

                        try {

                            geomType = GeometryData.GeomType.valueOf(type);
                            var selectedIndex = geomType.ordinal();

                            // set selected choice index and corresponding datablock
                            data.setIntValue(index++, selectedIndex);
                            var selectedData = geom.getComponent(selectedIndex).createDataBlock();
                            ((DataBlockMixed) geomData).setBlock(1, (AbstractDataBlock) selectedData);

                        } catch (IllegalArgumentException e) {

                            throw new ReaderException("Unsupported geometry type: " + type + " at " + xmlStreamReader.getLocalName());
                        }

                    } else if ("coordinates".equals(name)) {

                        hasCoords = true;
                        int depth = 0;
                        int lvl = -1;
                        int numPoints = 0;
                        int numRings = 1;
                        TDoubleList coordsList = null;

                        do {
                            var next = xmlStreamReader.next();

                            if (next == XMLStreamConstants.START_ELEMENT) {

                                lvl++;

                            } else if (next == XMLStreamConstants.END_ELEMENT) {

                                if (lvl == depth - 2) {
                                    index++;
                                    numRings++;
                                }

                                // add coordinates to datablock
                                if (depth == 0 || lvl == depth - 1) {

                                    if (depth == 0) // point
                                    {
                                        var pointCoords = ((DataBlockMixed) geomData).getUnderlyingObject()[1];
                                        ((DataBlockDouble) pointCoords).setUnderlyingObject(coordsList.toArray());
                                        index += coordsList.size();
                                    } else if (depth == 1) // linestring
                                    {
                                        geomData.setIntValue(1, numPoints);

                                        var lineData = ((DataBlockMixed) geomData).getUnderlyingObject()[1];
                                        var lineCoords = ((DataBlockMixed) lineData).getUnderlyingObject()[1];
                                        ((DataBlockDouble) lineCoords).setUnderlyingObject(coordsList.toArray());

                                        index += coordsList.size() + 1;
                                        numPoints = 0;
                                    } else if (depth == 2) // polygon
                                    {
                                        geomData.setIntValue(1, numRings);

                                        var polyData = ((DataBlockMixed) geomData).getUnderlyingObject()[1];
                                        var ringListData = ((DataBlockMixed) polyData).getUnderlyingObject()[1];

                                        var ringCoords = new DataBlockDouble(0);
                                        ringCoords.setUnderlyingObject(coordsList.toArray());
                                        var ringData = new DataBlockMixed(new DataBlockInt(1), ringCoords);
                                        ringData.setIntValue(0, numPoints);
                                        ((DataBlockList) ringListData).add(ringData);

                                        index += coordsList.size() + 1;
                                        numPoints = 0;
                                    }
                                }

                                lvl--;
                            } else {
                                depth = lvl;
                                if (coordsList == null)
                                    coordsList = new TDoubleArrayList();

                                int dims = 0;
                                while (xmlStreamReader.hasNext()) {

                                    try {
                                        double coordinates = xmlStreamReader.getElementAsDouble();
                                        coordsList.add(coordinates);
                                        dims++;
                                    } catch (XMLStreamException e) {
                                        throw new ReaderException("Invalid coordinate value at " + xmlStreamReader.getLocalName());
                                    }
                                }

                                if (dims != numDims)
                                    throw new ReaderException("Read " + dims + " coordinates but expected " + numDims + " at " + xmlStreamReader.getLocation().getLineNumber());
                                numPoints++;
                            }

                        } while (lvl >= 0);

                    } else
                        xmlStreamReader.skipElement();
                }
            } catch (XMLStreamException e) {

                throw new IOException(e);
            }

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


    public XmlDataParser() {
    }


    public XmlDataParser(XMLStreamReader2 xmlStreamReader) {
        this.xmlStreamReader = xmlStreamReader;
    }


    @Override
    protected void init() throws IOException {

        // No functionality to implement for initialization
    }


    @Override
    public void setInput(InputStream is) throws IOException {
        try {
            XMLInputFactory2 instance = (XMLInputFactory2) XMLInputFactory.newInstance();
            this.xmlStreamReader = (XMLStreamReader2) instance.createXMLStreamReader(is);
        } catch (XMLStreamException e) {
            throw new IOException(e);
        }
    }

    @Override
    public DataBlock parseNextBlock() throws IOException {
        try {
            return super.parseNextBlock();
        } catch (MalformedJsonException e) {
            // Fix error message advising switch to lenient mode
            var msg = e.getMessage().replaceAll("Use JsonReader.*malformed", "Malformed");
            throw new ReaderException(msg);
        } catch (IllegalStateException e) {
            throw new ReaderException(e);
        }
    }


    @Override
    public void close() throws IOException {
        if (xmlStreamReader != null) {
            try {
                xmlStreamReader.close();
            } catch (XMLStreamException e) {
                throw new IOException(e);
            }
        }
    }

    @Override
    public void visit(Boolean comp) {
        addToProcessorTree(new BooleanReader(comp.getName()));
    }


    @Override
    public void visit(Count comp) {
        IntegerReader reader = new IntegerReader(comp.getName());
        if (comp.isSetId())
            countReaders.put(comp.getId(), reader);
        addToProcessorTree(reader);
    }


    @Override
    public void visit(Quantity comp) {
        addToProcessorTree(new DoubleReader(comp.getName()));
    }


    @Override
    public void visit(Time comp) {
        if (!comp.isIsoTime())
            addToProcessorTree(new DoubleReader(comp.getName()));
        else
            addToProcessorTree(new IsoDateTimeReader(comp.getName()));
    }


    @Override
    public void visit(Category comp) {
        addToProcessorTree(new StringReader(comp.getName()));
    }


    @Override
    public void visit(Text comp) {
        addToProcessorTree(new StringReader(comp.getName()));
    }


    @Override
    public void visit(GeometryData geom) {
//        addToProcessorTree(new GeometryReader(geom));
        hasVarSizeArray = true;
        processorStack.pop();
    }


    @Override
    protected DataBlockProcessor.AtomProcessor getRangeProcessor(RangeComponent range) {
        return new RangeReader(range.getName());
    }


    @Override
    protected DataBlockProcessor.RecordProcessor getRecordProcessor(DataRecord dataRecord) {
        return new RecordReader(dataRecord.getName());
    }


    @Override
    protected DataBlockProcessor.RecordProcessor getVectorProcessor(Vector vector) {
        return new RecordReader(vector.getName());
    }


    @Override
    protected DataBlockProcessor.ChoiceProcessor getChoiceProcessor(DataChoice choice) {
        return new ChoiceReader(choice);
    }


    @Override
    protected DataBlockProcessor.ArrayProcessor getArrayProcessor(DataArray array) {
        return new ArrayReader(array.getName());
    }


    @Override
    protected DataBlockProcessor.ImplicitSizeProcessor getImplicitSizeProcessor(DataArray array) {
        throw new IllegalStateException("Implicit size not supported by JSON parser");
    }


    @Override
    protected DataBlockProcessor.ArraySizeSupplier getArraySizeSupplier(String refId) {
        IntegerReader sizeReader = countReaders.get(refId);
        return () -> sizeReader.val;
    }


    @Override
    protected boolean moreData() throws IOException {
        try {
            return xmlStreamReader.getEventType() != XMLStreamConstants.END_DOCUMENT && xmlStreamReader.hasNext();
        } catch (XMLStreamException e) {
            throw new IOException(e);
        }
    }
}
