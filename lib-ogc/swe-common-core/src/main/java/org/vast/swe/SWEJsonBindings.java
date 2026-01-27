/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.

Copyright (C) 2012-2019 Sensia Software LLC. All Rights Reserved.

******************************* END LICENSE BLOCK ***************************/

package org.vast.swe;

import java.io.IOException;
import java.time.OffsetDateTime;
import org.vast.data.DataBlockList;
import org.vast.data.DataComponentProperty;
import org.vast.data.DateTimeOrDouble;
import org.vast.data.EncodedValuesImpl;
import org.vast.data.JSONEncodingImpl;
import org.vast.data.SWEFactory;
import org.vast.data.TextEncodingImpl;
import org.vast.data.XMLEncodingImpl;
import org.vast.json.JsonInliningWriter;
import org.vast.json.JsonReaderWithBuffer;
import org.vast.swe.fast.JsonArrayDataParserGson;
import org.vast.swe.fast.JsonArrayDataWriterGson;
import org.vast.swe.fast.JsonDataParserGson;
import org.vast.swe.fast.JsonDataWriterGson;
import org.vast.unit.UnitParserUCUM;
import org.vast.util.DateTimeFormat;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import net.opengis.AbstractBindings;
import net.opengis.OgcProperty;
import net.opengis.OgcPropertyImpl;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;
import net.opengis.swe.v20.AbstractSWE;
import net.opengis.swe.v20.AbstractSWEIdentifiable;
import net.opengis.swe.v20.AllowedGeoms;
import net.opengis.swe.v20.SimpleComponent;
import net.opengis.swe.v20.AllowedTimes;
import net.opengis.swe.v20.AllowedTokens;
import net.opengis.swe.v20.AllowedValues;
import net.opengis.swe.v20.BinaryBlock;
import net.opengis.swe.v20.BinaryComponent;
import net.opengis.swe.v20.BinaryEncoding;
import net.opengis.swe.v20.Boolean;
import net.opengis.swe.v20.ByteEncoding;
import net.opengis.swe.v20.ByteOrder;
import net.opengis.swe.v20.Category;
import net.opengis.swe.v20.CategoryOrRange;
import net.opengis.swe.v20.CategoryRange;
import net.opengis.swe.v20.Count;
import net.opengis.swe.v20.CountOrRange;
import net.opengis.swe.v20.CountRange;
import net.opengis.swe.v20.DataArray;
import net.opengis.swe.v20.DataChoice;
import net.opengis.swe.v20.DataRecord;
import net.opengis.swe.v20.DataStream;
import net.opengis.swe.v20.EncodedValues;
import net.opengis.swe.v20.GeometryData.GeomType;
import net.opengis.swe.v20.Matrix;
import net.opengis.swe.v20.NilValue;
import net.opengis.swe.v20.NilValues;
import net.opengis.swe.v20.Quantity;
import net.opengis.swe.v20.QuantityOrRange;
import net.opengis.swe.v20.QuantityRange;
import net.opengis.swe.v20.ScalarComponent;
import net.opengis.swe.v20.Text;
import net.opengis.swe.v20.TextEncoding;
import net.opengis.swe.v20.Time;
import net.opengis.swe.v20.TimeOrRange;
import net.opengis.swe.v20.TimeRange;
import net.opengis.swe.v20.UnitReference;
import net.opengis.swe.v20.Vector;
import net.opengis.swe.v20.XMLEncoding;
import net.opengis.swe.v20.Factory;
import net.opengis.swe.v20.GeometryData;
import net.opengis.swe.v20.JSONEncoding;


@SuppressWarnings("javadoc")
public class SWEJsonBindings extends AbstractBindings
{
    public final static String NAN = "NaN";
    public final static String PLUS_INFINITY = "+Infinity";
    public final static String MINUS_INFINITY = "-Infinity";
    
    protected Factory factory;
    protected boolean enforceTypeFirst;
    

    public SWEJsonBindings()
    {
        this(true);
    }
    
    
    public SWEJsonBindings(boolean enforceTypeFirst)
    {
        this.factory = new SWEFactory();
        this.enforceTypeFirst = enforceTypeFirst;
    }
    
    
    public SWEJsonBindings(Factory factory)
    {
        this(factory, true);
    }
    
    
    public SWEJsonBindings(Factory factory, boolean enforceTypeFirst)
    {
        this.factory = factory;
        this.enforceTypeFirst = enforceTypeFirst;
    }
    
    
    
    /********************
     * Read methods
     *******************/
    
    public DataComponent readDataComponent(JsonReader reader) throws IOException
    {
        reader = beginObjectWithType(reader);
        var type = reader.nextString();
        
        var comp = readDataComponentByType(reader, type);
        if (comp == null)
            throw new JsonParseException("Invalid component type: " + type + " @ " + reader.getPath());
        
        return comp;
    }
    
    
    @SuppressWarnings("unchecked")
    public <T extends DataComponent> void readDataComponentOrLink(JsonReader reader, OgcProperty<T> prop) throws IOException
    {
        reader = beginObjectWithTypeOrLink(reader);
        
        if (isLink(reader))
        {
            var href = reader.nextString();
            prop.setHref(href);
            readLink(reader, prop);
        }
        else
        {
            var type = reader.nextString();
            var comp = readDataComponentByType(reader, type);
            if (comp == null)
                throw new JsonParseException("Invalid component type: " + type + " @ " + reader.getPath());
            
            prop.setValue((T)comp);
        }
    }
    
    
    public DataComponent readDataComponentByType(JsonReader reader, String type) throws IOException
    {
        if ("DataRecord".equals(type))
            return readDataRecord(reader);
        else if ("Vector".equals(type))
            return readVector(reader);
        else if ("DataArray".equals(type))
            return readDataArray(reader);
        else if ("Matrix".equals(type))
            return readMatrix(reader);
        else if ("DataChoice".equals(type))
            return readDataChoice(reader);
        else if ("Count".equals(type))
            return readCount(reader);
        else if ("CategoryRange".equals(type))
            return readCategoryRange(reader);
        else if ("QuantityRange".equals(type))
            return readQuantityRange(reader);
        else if ("Time".equals(type))
            return readTime(reader);
        else if ("TimeRange".equals(type))
            return readTimeRange(reader);
        else if ("Boolean".equals(type))
            return readBoolean(reader);
        else if ("Text".equals(type))
            return readText(reader);
        else if ("Category".equals(type))
            return readCategory(reader);
        else if ("Quantity".equals(type))
            return readQuantity(reader);
        else if ("CountRange".equals(type))
            return readCountRange(reader);
        else if ("Geometry".equals(type))
            return readGeometry(reader);
        else
            return null;
    }
    
    
    public boolean readAbstractSWEProperties(JsonReader reader, AbstractSWE bean, String name) throws IOException
    {
        // id
        if ("id".equals(name))
            bean.setId(trimStringValue(reader.nextString()));
        
        else
            return false;
        
        return true;
    }
    
    
    public boolean readAbstractSWEIdentifiableProperties(JsonReader reader, AbstractSWEIdentifiable bean, String name) throws IOException
    {
        // identifier
        if ("identifier".equals(name))
            bean.setIdentifier(trimStringValue(reader.nextString()));

        // label
        else if ("label".equals(name))
            bean.setLabel(trimStringValue(reader.nextString()));

        // description
        else if ("description".equals(name))
            bean.setDescription(trimStringValue(reader.nextString()));
        
        else
            return readAbstractSWEProperties(reader, bean, name);
        
        return true;
    }
    
    
    public boolean readAbstractDataComponentProperties(JsonReader reader, DataComponent bean, String name) throws IOException
    {
        // definition
        if ("definition".equals(name))
            bean.setDefinition(reader.nextString());
        
        // name
        else if ("name".equals(name))
            bean.setName(reader.nextString());
        
        // updatable
        else if ("updatable".equals(name))
            bean.setUpdatable(reader.nextBoolean());

        // optional
        else if ("optional".equals(name))
            bean.setOptional(reader.nextBoolean());

        else
            return readAbstractSWEIdentifiableProperties(reader, bean, name);
        
        return true;
    }
    
    
    public boolean readAbstractSimpleComponentProperties(JsonReader reader, SimpleComponent bean, String name) throws IOException
    {
        // referenceframe
        if ("referenceFrame".equals(name))
            bean.setReferenceFrame(reader.nextString());

        // axisid
        else if ("axisID".equals(name))
            bean.setAxisID(reader.nextString());

        // quality
        else if ("quality".equals(name))
        {
            reader.beginArray();
            
            while (reader.hasNext())
            {
                OgcProperty<SimpleComponent> qualityProp = new OgcPropertyImpl<SimpleComponent>();
                readDataComponentOrLink(reader, qualityProp);
                bean.getQualityList().add(qualityProp);
            }
            
            reader.endArray();
        }

        // nilValues
        else if ("nilValues".equals(name))
        {
            OgcProperty<NilValues> nilValuesProp = bean.getNilValuesProperty();
            nilValuesProp.setValue(readNilValues(reader));
        }
        
        else
            return readAbstractDataComponentProperties(reader, bean, name);
        
        return true;
    }
    
    
    public DataRecord readDataRecord(JsonReader reader) throws IOException
    {
        DataRecord bean = factory.newDataRecord();
        
        // start reading object unless already started
        if (reader.peek() == JsonToken.BEGIN_OBJECT)
            reader.beginObject();
        
        while (reader.hasNext())
        {
            String name = reader.nextName();
            if (!readDataRecordProperties(reader, bean, name))
                reader.skipValue();
        }
        reader.endObject();
        
        return bean;
    }
    
    
    public boolean readDataRecordProperties(final JsonReader reader, DataRecord bean, String name) throws IOException
    {
        if ("fields".equals(name))
        {
            reader.beginArray();
            
            while (reader.hasNext())
            {
                var fieldProp = new DataComponentProperty<DataComponent>();
                var comp = readDataComponent(reader);
                fieldProp.setName(comp.getName());
                fieldProp.setValue(comp);
                
                try {
                    bean.getFieldList().add(fieldProp);
                }
                catch (IllegalArgumentException e) {
                    throw new JsonParseException(reader.getPath() + ": " + e.getMessage());
                }
            }
            
            reader.endArray();
        }
        
        else
            return readAbstractDataComponentProperties(reader, bean, name);
        
        return true;
    }
    
    
    public Vector readVector(JsonReader reader) throws IOException
    {
        Vector bean = factory.newVector();

        // start reading object unless already started
        if (reader.peek() == JsonToken.BEGIN_OBJECT)
            reader.beginObject();
        
        while (reader.hasNext())
        {
            String name = reader.nextName();
            if (!readVectorProperties(reader, bean, name))
                reader.skipValue();
        }
        reader.endObject();

        return bean;
    }
    
    
    public boolean readVectorProperties(JsonReader reader, Vector bean, String name) throws IOException
    {
        // referenceframe
        if ("referenceFrame".equals(name))
            bean.setReferenceFrame(reader.nextString());

        // localframe
        else if ("localFrame".equals(name))
            bean.setLocalFrame(reader.nextString());
        
        // coordinates
        else if ("coordinates".equals(name))
        {
            reader.beginArray();
            
            while (reader.hasNext())
            {
                var coordProp = new DataComponentProperty<ScalarComponent>();
                var comp = readDataComponent(reader);
                if (!(comp instanceof Count || comp instanceof Quantity || comp instanceof Time))
                    throw new IOException("Invalid vector coordinate type: " + comp.getClass().getSimpleName());
                coordProp.setName(comp.getName());
                coordProp.setValue((ScalarComponent)comp);
                bean.getCoordinateList().add(coordProp);
            }
            
            reader.endArray();
        }
        
        else
            return readAbstractDataComponentProperties(reader, bean, name);
        
        return true;
    }
    
    
    public DataChoice readDataChoice(JsonReader reader) throws IOException
    {
        DataChoice bean = factory.newDataChoice();
        
        // start reading object unless already started
        if (reader.peek() == JsonToken.BEGIN_OBJECT)
            reader.beginObject();
        
        while (reader.hasNext())
        {
            String name = reader.nextName();
            if (!readDataChoiceProperties(reader, bean, name))
                reader.skipValue();
        }
        reader.endObject();

        return bean;
    }
    
    
    public boolean readDataChoiceProperties(JsonReader reader, DataChoice bean, String name) throws IOException
    {
        // choiceValue
        if ("choiceValue".equals(name))
        {
            bean.setChoiceValue(readCategory(reader));
        }

        // item
        else if ("items".equals(name))
        {
            reader.beginArray();
            
            while (reader.hasNext())
            {
                var itemProp = new DataComponentProperty<DataComponent>();
                var comp = readDataComponent(reader);
                itemProp.setName(comp.getName());
                itemProp.setValue(comp);
                bean.getItemList().add(itemProp);
            }
            
            reader.endArray();
        }
        
        else
            return readAbstractDataComponentProperties(reader, bean, name);
            
        return true;
    }
    
    
    public DataArray readDataArray(JsonReader reader) throws IOException
    {
        DataArray bean = factory.newDataArray();
        
        // start reading object unless already started
        if (reader.peek() == JsonToken.BEGIN_OBJECT)
            reader.beginObject();
        
        while (reader.hasNext())
        {
            String name = reader.nextName();
            if (!readDataArrayProperties(reader, bean, name))
                reader.skipValue();
        }
        reader.endObject();

        return bean;
    }
    
    
    public boolean readDataArrayProperties(JsonReader reader, DataArray bean, String name) throws IOException
    {
        // elementCount
        if ("elementCount".equals(name))
        {
            OgcProperty<Count> elementCountProp = bean.getElementCountProperty();
            reader = beginObjectWithTypeOrLink(reader);
            if (isLink(reader))
            {
                elementCountProp.setHref(reader.nextString());
                readLink(reader, elementCountProp);
            }
            else
            {
                var type = reader.nextString(); // ignore type and always parse Count
                if (!"Count".equals(type))
                    throw new IOException("Expected Count component");
                elementCountProp.setValue(readCount(reader));
            }
        }

        // elementType
        else if ("elementType".equals(name))
        {
            OgcProperty<DataComponent> elementTypeProp = bean.getElementTypeProperty();
            var comp = readDataComponent(reader);
            elementTypeProp.setName(comp.getName());
            elementTypeProp.setValue(comp);
        }

        // encoding
        else if ("encoding".equals(name))
        {
            bean.setEncoding(readEncoding(reader));
        }

        // values
        else if ("values".equals(name))
        {
            EncodedValues values = readEncodedValuesProperty(reader, bean, bean.getEncoding());
            bean.setValues(values);
        }
        
        else
            return readAbstractDataComponentProperties(reader, bean, name);
        
        return true;
    }
    
    
    public Matrix readMatrix(JsonReader reader) throws IOException
    {
        Matrix bean = factory.newMatrix();

        // start reading object unless already started
        if (reader.peek() == JsonToken.BEGIN_OBJECT)
            reader.beginObject();
        
        while (reader.hasNext())
        {
            String name = reader.nextName();
            if (!readMatrixProperties(reader, bean, name))
                reader.skipValue();
        }
        reader.endObject();

        return bean;
    }
    
    
    public boolean readMatrixProperties(JsonReader reader, Matrix bean, String name) throws IOException
    {
        // referenceframe
        if ("referenceFrame".equals(name))
            bean.setReferenceFrame(reader.nextString());

        // localframe
        else if ("localFrame".equals(name))
            bean.setLocalFrame(reader.nextString());
        
        else
            return readDataArrayProperties(reader, bean, name);
        
        return true;
    }
    
    
    public GeometryData readGeometry(JsonReader reader) throws IOException
    {
        GeometryData bean = factory.newGeometry();

        // start reading object unless already started
        if (reader.peek() == JsonToken.BEGIN_OBJECT)
            reader.beginObject();
        
        while (reader.hasNext())
        {
            String name = reader.nextName();
            if (!readGeometryProperties(reader, bean, name))
                reader.skipValue();
        }
        reader.endObject();

        return bean;
    }
    
    
    public boolean readGeometryProperties(JsonReader reader, GeometryData bean, String name) throws IOException
    {
        // referenceframe
        if ("srs".equals(name))
            bean.setReferenceFrame(reader.nextString());
        
        // constraint
        else if ("constraint".equals(name))
        {
            OgcProperty<AllowedGeoms> constraintProp = bean.getConstraintProperty();
            constraintProp.setValue(readAllowedGeoms(reader));
        }
        
        // value
        else if ("value".equals(name))
        {
            var dataParser = new JsonDataParserGson(reader);
            dataParser.setDataComponents(bean);
            var data = dataParser.parseNextBlock();
            bean.setData(data);
        }
        
        else
            return readAbstractDataComponentProperties(reader, bean, name);
        
        return true;
    }
    
    
    public DataStream readDataStream(JsonReader reader) throws IOException
    {
        DataStream bean = factory.newDataStream();

        // start reading object unless already started
        if (reader.peek() == JsonToken.BEGIN_OBJECT)
            reader.beginObject();
        
        while (reader.hasNext())
        {
            String name = reader.nextName();
            if (!readDataStreamProperties(reader, bean, name))
                reader.skipValue();
        }
        reader.endObject();

        return bean;
    }
    
    
    public boolean readDataStreamProperties(JsonReader reader, DataStream bean, String name) throws IOException
    {
        // elementCount
        if ("elementCount".equals(name))
        {
            bean.setElementCount(readCount(reader));
        }

        // elementType
        else if ("elementType".equals(name))
        {
            OgcProperty<DataComponent> elementTypeProp = bean.getElementTypeProperty();
            var comp = readDataComponent(reader);
            elementTypeProp.setName(comp.getName());
            elementTypeProp.setValue(comp);
        }

        // encoding
        else if ("encoding".equals(name))
        {
            bean.setEncoding(readEncoding(reader));
        }

        // values
        else if ("values".equals(name))
        {
            EncodedValues values = readEncodedValuesProperty(reader, bean, bean.getEncoding());
            bean.setValues(values);
        }
        
        else
            return readAbstractSWEIdentifiableProperties(reader, bean, name);
        
        return true;
    }
    
    
    public Boolean readBoolean(JsonReader reader) throws IOException
    {
        Boolean bean = factory.newBoolean();

        // start reading object unless already started
        if (reader.peek() == JsonToken.BEGIN_OBJECT)
            reader.beginObject();
        
        while (reader.hasNext())
        {
            String name = reader.nextName();
            if (!readBooleanProperties(reader, bean, name))
                reader.skipValue();
        }
        reader.endObject();

        return bean;
    }
    
    
    public boolean readBooleanProperties(JsonReader reader, Boolean bean, String name) throws IOException
    {
        if ("value".equals(name))
            bean.setValue(reader.nextBoolean());
        
        else
            return readAbstractSimpleComponentProperties(reader, bean, name);
        
        return true;
    }
    
    
    public Text readText(JsonReader reader) throws IOException
    {
        Text bean = factory.newText();

        // start reading object unless already started
        if (reader.peek() == JsonToken.BEGIN_OBJECT)
            reader.beginObject();
        
        while (reader.hasNext())
        {
            String name = reader.nextName();
            if (!readTextProperties(reader, bean, name))
                reader.skipValue();
        }
        reader.endObject();

        return bean;
    }
    
    
    public boolean readTextProperties(JsonReader reader, Text bean, String name) throws IOException
    {
        // constraint
        if ("constraint".equals(name))
        {
            OgcProperty<AllowedTokens> constraintProp = bean.getConstraintProperty();
            constraintProp.setValue(readAllowedTokens(reader));
        }

        // value
        else if ("value".equals(name))
            bean.setValue(trimStringValue(reader.nextString()));
        
        else
            return readAbstractSimpleComponentProperties(reader, bean, name);
        
        return true;
    }
    
    
    public Count readCount(JsonReader reader) throws IOException
    {
        Count bean = factory.newCount();

        // start reading object unless already started
        if (reader.peek() == JsonToken.BEGIN_OBJECT)
            reader.beginObject();
        
        while (reader.hasNext())
        {
            String name = reader.nextName();
            if (!readCountProperties(reader, bean, name))
                reader.skipValue();
        }
        reader.endObject();
        
        return bean;
    }
    
    
    protected boolean readCountBaseProperties(JsonReader reader, CountOrRange bean, String name) throws IOException
    {
        // constraint
        if ("constraint".equals(name))
        {
            OgcProperty<AllowedValues> constraintProp = bean.getConstraintProperty();
            constraintProp.setValue(readAllowedValues(reader));
        }
        
        else
            return readAbstractSimpleComponentProperties(reader, bean, name);
        
        return true;
    }
    
    
    public boolean readCountProperties(JsonReader reader, Count bean, String name) throws IOException
    {
        // value
        if ("value".equals(name))
            bean.setValue(reader.nextInt());
        
        else
            return readCountBaseProperties(reader, bean, name);
        
        return true;
    }
    
    
    public CountRange readCountRange(JsonReader reader) throws IOException
    {
        CountRange bean = factory.newCountRange();

        // start reading object unless already started
        if (reader.peek() == JsonToken.BEGIN_OBJECT)
            reader.beginObject();
        
        while (reader.hasNext())
        {
            String name = reader.nextName();
            if (!readCountRangeProperties(reader, bean, name))
                reader.skipValue();
        }
        reader.endObject();

        return bean;
    }
    
    
    public boolean readCountRangeProperties(JsonReader reader, CountRange bean, String name) throws IOException
    {
        // value
        if ("value".equals(name))
        {
            reader.beginArray();
            int min = reader.nextInt();
            int max = reader.nextInt();
            bean.setValue(new int[] {min, max});
            reader.endArray();
        }
        
        else
            return readCountBaseProperties(reader, bean, name);
        
        return true;
    }
    
    
    public Category readCategory(JsonReader reader) throws IOException
    {
        Category bean = factory.newCategory();

        // start reading object unless already started
        if (reader.peek() == JsonToken.BEGIN_OBJECT)
            reader.beginObject();
        
        while (reader.hasNext())
        {
            String name = reader.nextName();
            if (!readCategoryProperties(reader, bean, name))
                reader.skipValue();
        }
        reader.endObject();

        return bean;
    }
    
    
    protected boolean readCategoryBaseProperties(JsonReader reader, CategoryOrRange bean, String name) throws IOException
    {
        // codeSpace
        if ("codeSpace".equals(name))
            bean.setCodeSpace(reader.nextString());
        
        // constraint
        else if ("constraint".equals(name))
        {
            OgcProperty<AllowedTokens> constraintProp = bean.getConstraintProperty();
            constraintProp.setValue(readAllowedTokens(reader));
        }
        
        else
            return readAbstractSimpleComponentProperties(reader, bean, name);
        
        return true;
    }
    
    
    public boolean readCategoryProperties(JsonReader reader, Category bean, String name) throws IOException
    {
        // value
        if ("value".equals(name))
            bean.setValue(reader.nextString());
        
        else
            return readCategoryBaseProperties(reader, bean, name);
        
        return true;
    }
    
    
    public CategoryRange readCategoryRange(JsonReader reader) throws IOException
    {
        CategoryRange bean = factory.newCategoryRange();

        // start reading object unless already started
        if (reader.peek() == JsonToken.BEGIN_OBJECT)
            reader.beginObject();
        
        while (reader.hasNext())
        {
            String name = reader.nextName();
            if (!readCategoryRangeProperties(reader, bean, name))
                reader.skipValue();
        }
        reader.endObject();

        return bean;
    }
    
    
    public boolean readCategoryRangeProperties(JsonReader reader, CategoryRange bean, String name) throws IOException
    {
        // value
        if ("value".equals(name))
        {
            reader.beginArray();
            String min = reader.nextString();
            String max = reader.nextString();
            bean.setValue(new String[] {min, max});
            reader.endArray();
        }
        
        else
            return readCategoryBaseProperties(reader, bean, name);
        
        return true;
    }
    
    
    public Quantity readQuantity(JsonReader reader) throws IOException
    {
        Quantity bean = factory.newQuantity();

        // start reading object unless already started
        if (reader.peek() == JsonToken.BEGIN_OBJECT)
            reader.beginObject();
        
        while (reader.hasNext())
        {
            String name = reader.nextName();
            if (!readQuantityProperties(reader, bean, name))
                reader.skipValue();
        }
        reader.endObject();

        return bean;
    }
    
    
    protected boolean readQuantityBaseProperties(JsonReader reader, QuantityOrRange bean, String name) throws IOException
    {
        // uom
        if ("uom".equals(name))
        {
            bean.setUom(readUnitReference(reader));
        }
        
        // constraint
        else if ("constraint".equals(name))
        {
            OgcProperty<AllowedValues> constraintProp = bean.getConstraintProperty();
            constraintProp.setValue(readAllowedValues(reader));
        }
        
        else
            return readAbstractSimpleComponentProperties(reader, bean, name);
        
        return true;
    }
    
    
    public boolean readQuantityProperties(JsonReader reader, Quantity bean, String name) throws IOException
    {
        // value
        if ("value".equals(name))
        {
            var val = readNumberOrSpecial(reader);
            bean.setValue(val);
        }
        
        else
            return readQuantityBaseProperties(reader, bean, name);
        
        return true;
    }
    
    
    public QuantityRange readQuantityRange(JsonReader reader) throws IOException
    {
        QuantityRange bean = factory.newQuantityRange();

        // start reading object unless already started
        if (reader.peek() == JsonToken.BEGIN_OBJECT)
            reader.beginObject();
        
        while (reader.hasNext())
        {
            String name = reader.nextName();
            if (!readQuantityRangeProperties(reader, bean, name))
                reader.skipValue();
        }
        reader.endObject();

        return bean;
    }
    
    
    public boolean readQuantityRangeProperties(JsonReader reader, QuantityRange bean, String name) throws IOException
    {
        // value
        if ("value".equals(name))
        {
            var strictness = reader.getStrictness();
            reader.setStrictness(Strictness.LENIENT);
            
            reader.beginArray();
            double min = readNumberOrSpecial(reader);
            double max = readNumberOrSpecial(reader);
            bean.setValue(new double[] {min, max});
            reader.endArray();
            
            reader.setStrictness(strictness);
        }
        
        else
            return readQuantityBaseProperties(reader, bean, name);
        
        return true;
    }
    
    
    public Time readTime(JsonReader reader) throws IOException
    {
        Time bean = factory.newTime();

        // start reading object unless already started
        if (reader.peek() == JsonToken.BEGIN_OBJECT)
            reader.beginObject();
        
        while (reader.hasNext())
        {
            String name = reader.nextName();
            if (!readTimeProperties(reader, bean, name))
                reader.skipValue();
        }
        reader.endObject();

        return bean;
    }
    
    
    protected boolean readTimeBaseProperties(JsonReader reader, TimeOrRange bean, String name) throws IOException
    {
        // referencetime
        if ("referenceTime".equals(name))
            bean.setReferenceTime(getDateTimeFromString(reader.nextString()));

        // localframe
        else if ("localFrame".equals(name))
            bean.setLocalFrame(reader.nextString());

        // uom
        else if ("uom".equals(name))
            bean.setUom(readUnitReference(reader));
        
        // constraint
        else if ("constraint".equals(name))
        {
            OgcProperty<AllowedTimes> constraintProp = bean.getConstraintProperty();
            constraintProp.setValue(readAllowedTimes(reader, bean.isIsoTime()));
        }
        
        else
            return readAbstractSimpleComponentProperties(reader, bean, name);
        
        return true;
    }
    
    
    public boolean readTimeProperties(JsonReader reader, Time bean, String name) throws IOException
    {
        // value
        if ("value".equals(name))
            bean.setValue(readTimeVal(reader, bean.isIsoTime()));
        
        else
            return readTimeBaseProperties(reader, bean, name);
        
        return true;
    }
    
    
    public TimeRange readTimeRange(JsonReader reader) throws IOException
    {
        TimeRange bean = factory.newTimeRange();

        // start reading object unless already started
        if (reader.peek() == JsonToken.BEGIN_OBJECT)
            reader.beginObject();
        
        while (reader.hasNext())
        {
            String name = reader.nextName();
            if (!readTimeRangeProperties(reader, bean, name))
                reader.skipValue();
        }
        reader.endObject();

        return bean;
    }
    
    
    public boolean readTimeRangeProperties(JsonReader reader, TimeRange bean, String name) throws IOException
    {
        // value
        if ("value".equals(name))
        {
            reader.beginArray();
            var min = readTimeVal(reader, bean.isIsoTime());
            var max = readTimeVal(reader, bean.isIsoTime());
            bean.setValue(new DateTimeOrDouble[] {min, max});
            reader.endArray();
        }
        
        else
            return readTimeBaseProperties(reader, bean, name);
        
        return true;
    }
    
    
    public NilValues readNilValues(JsonReader reader) throws IOException
    {
        NilValues bean = factory.newNilValues();

        reader.beginArray();
        while (reader.hasNext())
            bean.addNilValue(readNilValue(reader));
        reader.endArray();

        return bean;
    }
    
    
    public NilValue readNilValue(JsonReader reader) throws IOException
    {
        NilValue bean = factory.newNilValue();

        reader.beginObject();
        while (reader.hasNext())
        {
            String name = reader.nextName();
            if (!readNilValueProperties(reader, bean, name))
                reader.skipValue();
        }
        reader.endObject();

        return bean;
    }
    
    
    public boolean readNilValueProperties(JsonReader reader, NilValue bean, String name) throws IOException
    {
        if ("reason".equals(name))
            bean.setReason(reader.nextString());
        
        else if ("value".equals(name))
        {
            if (reader.peek() == JsonToken.NUMBER)
                bean.setValue(getStringValue(reader.nextDouble()));
            else
                bean.setValue(reader.nextString());
        }
        
        return true;
    }
    
    
    public AllowedTokens readAllowedTokens(JsonReader reader) throws IOException
    {
        AllowedTokens bean = factory.newAllowedTokens();

        if (reader.peek() == JsonToken.BEGIN_OBJECT)
            reader.beginObject();
        
        while (reader.hasNext())
        {
            String name = reader.nextName();
            if (!readAllowedTokensProperties(reader, bean, name))
                reader.skipValue();
        }
        reader.endObject();

        return bean;
    }
    
    
    public boolean readAllowedTokensProperties(JsonReader reader, AllowedTokens bean, String name) throws IOException
    {
        // values
        if ("values".equals(name))
        {
            reader.beginArray();
            while (reader.hasNext())
                bean.addValue(trimStringValue(reader.nextString()));
            reader.endArray();
        }
        
        // pattern
        else if ("pattern".equals(name))
        {
            bean.setPattern(trimStringValue(reader.nextString()));
        }
        
        else
            return readAbstractSWEProperties(reader, bean, name);
        
        return true;
    }
    
    
    public AllowedValues readAllowedValues(JsonReader reader) throws IOException
    {
        AllowedValues bean = factory.newAllowedValues();

        if (reader.peek() == JsonToken.BEGIN_OBJECT)
            reader.beginObject();
        
        while (reader.hasNext())
        {
            String name = reader.nextName();
            if (!readAllowedValuesProperties(reader, bean, name))
                reader.skipValue();
        }
        reader.endObject();

        return bean;
    }
    
    
    public boolean readAllowedValuesProperties(JsonReader reader, AllowedValues bean, String name) throws IOException
    {
        var strictness = reader.getStrictness();
        reader.setStrictness(Strictness.LENIENT);
        
        // values
        if ("values".equals(name))
        {
            reader.beginArray();
            while (reader.hasNext())
            {
                var val = readNumberOrSpecial(reader);
                bean.addValue(val);
            }
            reader.endArray();
        }
        
        // intervals
        else if ("intervals".equals(name))
        {
            reader.beginArray();
            while (reader.hasNext())
            {
                reader.beginArray();
                var min = readNumberOrSpecial(reader);
                var max = readNumberOrSpecial(reader);
                bean.addInterval(new double[] {min, max});
                reader.endArray();
            }
            reader.endArray();
        }
        
        // significantFigures
        else if ("significantFigures".equals(name))
        {
            bean.setSignificantFigures(reader.nextInt());
        }
        
        else
        {
            reader.setStrictness(strictness);
            return readAbstractSWEProperties(reader, bean, name);
        }
        
        reader.setStrictness(strictness);
        return true;
    }
    
    
    public AllowedTimes readAllowedTimes(JsonReader reader, boolean isIso) throws IOException
    {
        AllowedTimes bean = factory.newAllowedTimes();

        if (reader.peek() == JsonToken.BEGIN_OBJECT)
            reader.beginObject();
        
        while (reader.hasNext())
        {
            String name = reader.nextName();
            if (!readAllowedTimesProperties(reader, bean, isIso, name))
                reader.skipValue();
        }
        reader.endObject();

        return bean;
    }
    
    
    public boolean readAllowedTimesProperties(JsonReader reader, AllowedTimes bean, boolean isIso, String name) throws IOException
    {
        // values
        if ("values".equals(name))
        {
            reader.beginArray();
            while (reader.hasNext())
                bean.addValue(readTimeVal(reader, isIso));
            reader.endArray();
        }
        
        // intervals
        else if ("intervals".equals(name))
        {
            reader.beginArray();
            while (reader.hasNext())
            {
                reader.beginArray();
                var min = readTimeVal(reader, isIso);
                var max = readTimeVal(reader, isIso);
                bean.addInterval(new DateTimeOrDouble[] {min, max});
                reader.endArray();
            }
            reader.endArray();
        }
        
        // significantFigures
        else if ("significantFigures".equals(name))
        {
            bean.setSignificantFigures(reader.nextInt());
        }
        
        else
            return readAbstractSWEProperties(reader, bean, name);
        
        return true;
    }
    
    
    public AllowedGeoms readAllowedGeoms(JsonReader reader) throws IOException
    {
        AllowedGeoms bean = factory.newAllowedGeoms();

        if (reader.peek() == JsonToken.BEGIN_OBJECT)
            reader.beginObject();
        
        while (reader.hasNext())
        {
            String name = reader.nextName();
            if (!readAllowedGeomsProperties(reader, bean, name))
                reader.skipValue();
        }
        reader.endObject();

        return bean;
    }
    
    
    public boolean readAllowedGeomsProperties(JsonReader reader, AllowedGeoms bean, String name) throws IOException
    {
        if ("geomTypes".equals(name))
        {
            reader.beginArray();
            while (reader.hasNext())
                bean.addGeomType(GeomType.valueOf(reader.nextString()));
            reader.endArray();
        }
        
        else
            return readAbstractSWEProperties(reader, bean, name);
        
        return true;
    }
    
    
    public UnitReference readUnitReference(JsonReader reader) throws IOException
    {
        UnitReference bean = factory.newUnitReference();

        reader.beginObject();
        while (reader.hasNext())
        {
            String name = reader.nextName();
            if (!readUnitReferenceProperties(reader, bean, name))
                reader.skipValue();
        }
        reader.endObject();

        return bean;
    }
    
    
    public boolean readUnitReferenceProperties(JsonReader reader, UnitReference bean, String name) throws IOException
    {
        // code 
        if ("code".equals(name))
        {
            var ucumCode = reader.nextString();
            if (!UnitParserUCUM.isValidUnit(ucumCode))
                throw new IOException("Invalid UCUM code '" + ucumCode + "'");
            bean.setCode(ucumCode);
        }
        
        else if ("href".equals(name))
        {
            bean.setHref(reader.nextString());
        }
        
        else
            return false;
        
        return true;
    }
    
    
    protected void readLink(JsonReader reader, OgcProperty<?> prop) throws IOException
    {
        if (reader.peek() == JsonToken.BEGIN_OBJECT)
            reader.beginObject();
        
        while (reader.hasNext())
        {
            var name = reader.nextName();
            
            if (!readLinkProperties(reader, prop, name))
                reader.skipValue();
        }
        reader.endObject();
    }
    
    
    protected boolean readLinkProperties(JsonReader reader, OgcProperty<?> bean, String name) throws IOException
    {
        if ("href".equals(name))
        {
            bean.setHref(reader.nextString());
        }
        else if ("name".equals(name))
        {
            bean.setName(reader.nextString());
        }
        else if ("title".equals(name))
        {
            bean.setTitle(reader.nextString());
        }
        else
            return false;
        
        return true;
    }
    
    
    public EncodedValues readEncodedValuesProperty(JsonReader reader, AbstractSWEIdentifiable blockComponent, DataEncoding encoding) throws IOException
    {
        EncodedValues bean = factory.newEncodedValuesProperty();
        
        if (reader.peek() == JsonToken.BEGIN_ARRAY)
        {
            reader.beginArray();
            
            if (blockComponent instanceof DataArray)
            {
                var dataArray = (DataArray)blockComponent;
                var parser = new JsonArrayDataParserGson(reader);
                parser.setDataComponents(dataArray.getElementType());
                parser.setRenewDataBlock(true);
                
                var dataList = new DataBlockList(true);
                while (reader.hasNext())
                {
                    var rec = parser.parseNextBlock();
                    dataList.add(rec);
                }
                dataArray.setData(dataList);
            }
            else if (blockComponent instanceof DataStream)
            {
                var dataStream = (DataStream)blockComponent;
                var parser = new JsonArrayDataParserGson(reader);
                parser.setDataComponents(dataStream.getElementType());
                parser.setRenewDataBlock(true);
                
                var dataList = new DataBlockList(true);
                while (reader.hasNext())
                {
                    var rec = parser.parseNextBlock();
                    dataList.add(rec);
                }
                
                bean.setData(dataList);
            }
            
            reader.endArray();
        }
        else
        {
            readLink(reader, bean);
        }
        
        return bean;
    }
    
    
    public DataEncoding readEncoding(JsonReader reader) throws IOException
    {
        reader = beginObjectWithType(reader);
        var type = reader.nextString();
        
        return readEncodingByType(reader, type);
    }
    
    
    public DataEncoding readEncodingByType(JsonReader reader, String type) throws IOException
    {
        if ("TextEncoding".equals(type))
            return readTextEncoding(reader);
        else if ("JSONEncoding".equals(type))
            return readJsonEncoding(reader);
        else if ("XMLEncoding".equals(type))
            return readXmlEncoding(reader);
        else if ("BinaryEncoding".equals(type))
            return readBinaryEncoding(reader);
        else
            throw new JsonParseException("Invalid component type: " + type + " @ " + reader.getPath());
    }
    
    
    public TextEncoding readTextEncoding(JsonReader reader) throws IOException
    {
        var bean = factory.newTextEncoding();
        
        while (reader.hasNext())
        {
            var name = reader.nextName();
            
            if ("collapseWhiteSpaces".equals(name))
                bean.setCollapseWhiteSpaces(reader.nextBoolean());
            else if ("decimalSeparator".equals(name))
                bean.setDecimalSeparator(reader.nextString());
            else if ("tokenSeparator".equals(name))
                bean.setTokenSeparator(reader.nextString());
            else if ("blockSeparator".equals(name))
                bean.setBlockSeparator(reader.nextString());
            else
                reader.skipValue();
        }
        
        reader.endObject();
        return bean;
    }
    
    
    public JSONEncoding readJsonEncoding(JsonReader reader) throws IOException
    {
        return new JSONEncodingImpl();
    }
    
    
    public XMLEncoding readXmlEncoding(JsonReader reader) throws IOException
    {
        return new XMLEncodingImpl();
    }
    
    
    public BinaryEncoding readBinaryEncoding(JsonReader reader) throws IOException
    {
        var bean = factory.newBinaryEncoding();
        
        while (reader.hasNext())
        {
            var name = reader.nextName();
            
            if ("byteOrder".equals(name))
            {
                var val = reader.nextString();
                try {
                    bean.setByteOrder(ByteOrder.fromString(val));
                } catch (IllegalArgumentException e){
                    throw new JsonParseException("Invalid byte order value: " + val + " @ " + reader.getPath());
                }
            }
            else if ("byteEncoding".equals(name))
            {
                var val = reader.nextString();
                try {
                    bean.setByteEncoding(ByteEncoding.fromString(val));
                } catch (IllegalArgumentException e){
                    throw new JsonParseException("Invalid byte encoding: " + val + " @ " + reader.getPath());
                }
            }
            else if ("byteLength".equals(name))
            {
                bean.setByteLength(reader.nextInt());
            }
            else if ("members".equals(name))
            {
                reader.beginArray();
                while (reader.hasNext())
                {
                    reader = beginObjectWithType(reader);
                    var type = reader.nextString();
                    
                    if ("Component".equals(type))
                        bean.addMemberAsComponent(readBinaryComponent(reader));
                    else if ("Block".equals(type))
                        bean.addMemberAsBlock(readBinaryBlock(reader));
                    else
                        throw new JsonParseException("Invalid binary component type: " + type + " @ " + reader.getPath());
                }
                reader.endArray();
            }
            else
                reader.skipValue();
        }
        
        reader.endObject();
        return bean;
    }
    
    
    protected BinaryComponent readBinaryComponent(JsonReader reader) throws IOException
    {
        var bean = factory.newBinaryComponent();

        if (reader.peek() == JsonToken.BEGIN_OBJECT)
            reader.beginObject();
        
        while (reader.hasNext())
        {
            String name = reader.nextName();
            if ("ref".equals(name))
                bean.setRef(reader.nextString());
            else if ("dataType".equals(name))
                bean.setDataType(reader.nextString());
            else if ("bitLength".equals(name))
                bean.setBitLength(reader.nextInt());
            else if ("byteLength".equals(name))
                bean.setByteLength(reader.nextInt());
            else if ("significantBits".equals(name))
                bean.setSignificantBits(reader.nextInt());
            else if ("encryption".equals(name))
                bean.setEncryption(reader.nextString());
            else
                reader.skipValue();
        }
        reader.endObject();
        return bean;
    }
    
    
    protected BinaryBlock readBinaryBlock(JsonReader reader) throws IOException
    {
        var bean = factory.newBinaryBlock();

        if (reader.peek() == JsonToken.BEGIN_OBJECT)
            reader.beginObject();
        
        while (reader.hasNext())
        {
            String name = reader.nextName();
            if ("ref".equals(name))
                bean.setRef(reader.nextString());
            else if ("byteLength".equals(name))
                bean.setByteLength(reader.nextInt());
            else if ("paddingBytes-before".equals(name))
                bean.setPaddingBytesBefore(reader.nextInt());
            else if ("paddingBytes-after".equals(name))
                bean.setPaddingBytesAfter(reader.nextInt());
            else if ("compression".equals(name))
                bean.setCompression(reader.nextString());
            else if ("encryption".equals(name))
                bean.setEncryption(reader.nextString());
            else
                reader.skipValue();
        }
        reader.endObject();
        return bean;
    }
    
    
    public JsonReader beginObjectWithTypeOrLink(JsonReader reader) throws IOException
    {
        return beginObjectWithType(reader, true);
    }
    
    
    public JsonReader beginObjectWithType(JsonReader reader) throws IOException
    {
        return beginObjectWithType(reader, false);
    }
    
    
    protected JsonReader beginObjectWithType(JsonReader reader, boolean linkAllowed) throws IOException
    {
        if (reader.peek() == JsonToken.BEGIN_OBJECT)
            reader.beginObject();
        
        boolean bufferedReaderCreated = false;
        while (reader.hasNext())
        {
            String propName = reader.nextName();
            
            if ("type".equals(propName))
            {
                if (bufferedReaderCreated)
                    ((JsonReaderWithBuffer)reader).startReplay();
                return reader;
            }
            else if (linkAllowed && "href".equals(propName))
            {
                if (bufferedReaderCreated)
                    ((JsonReaderWithBuffer)reader).startReplay();
                return reader;
            }
            else
            {
                if (linkAllowed || !enforceTypeFirst)
                {
                    if (!bufferedReaderCreated)
                    {
                        reader = new JsonReaderWithBuffer(reader);
                        bufferedReaderCreated = true;
                    }
                    ((JsonReaderWithBuffer)reader).buffer(propName);
                }
                else
                {
                    throw new JsonParseException("Required 'type' property as first member of JSON object @ " + reader.getPath());
                }
            }
        }
        
        throw new JsonParseException("Missing 'type' property in JSON object @ " + reader.getPath());
    }
    
    
    public boolean isLink(JsonReader reader)
    {
        return reader.getPath().endsWith("href");
    }
    
    
    public void checkObjectType(JsonReader reader, String expectedType) throws IOException
    {
        reader = beginObjectWithType(reader);
        var type = reader.nextString();
        if (!expectedType.equals(type))
            throw new JsonParseException(expectedType + " object expected but was " + type + " @ " + reader.getPath());
    }
    
    
    protected DateTimeOrDouble readTimeVal(JsonReader reader, boolean isIso) throws IOException
    {
        if (reader.peek() == JsonToken.NUMBER)
        {
            return new DateTimeOrDouble(reader.nextDouble());
        }
        else
        {
            var timeStr = reader.nextString().trim();
            
            if (isIso)
            {
                if (PLUS_INFINITY.equals(timeStr))
                    return new DateTimeOrDouble(OffsetDateTime.MAX);
                else if (MINUS_INFINITY.equals(timeStr))
                    return new DateTimeOrDouble(OffsetDateTime.MIN);
                else
                    return new DateTimeOrDouble(OffsetDateTime.parse(timeStr, DateTimeFormat.ISO_DATE_OR_TIME_FORMAT));
            }
            else
            {
                if (PLUS_INFINITY.equals(timeStr))
                    return new DateTimeOrDouble(Double.POSITIVE_INFINITY);
                else if (MINUS_INFINITY.equals(timeStr))
                    return new DateTimeOrDouble(Double.NEGATIVE_INFINITY);
                else if (NAN.equals(timeStr))
                    return new DateTimeOrDouble(Double.NaN);
                else
                    throw new JsonParseException("Invalid datetime value: " + timeStr);
            }
        }
    }
    
    
    protected double readNumberOrSpecial(JsonReader reader) throws IOException
    {
        if (reader.peek() == JsonToken.STRING)
        {
            var str = reader.nextString();
            if (PLUS_INFINITY.equals(str))
                return Double.POSITIVE_INFINITY;
            else if (MINUS_INFINITY.equals(str))
                return Double.NEGATIVE_INFINITY;
            else if (NAN.equals(str))
                return Double.NaN;
            else
                throw new JsonParseException("Invalid special number string: " + str);
        }
        else
            return reader.nextDouble();
    }


    /********************
     * Write methods
     *******************/
    
    public void writeAbstractSWEProperties(JsonWriter writer, AbstractSWE bean) throws IOException
    {
        // id
        if (bean.isSetId())
            writer.name("id").value(bean.getId());
    
        // extensions
        boolean extObjAdded = false;
        int numItems = bean.getExtensionList().size();
        for (int i = 0; i < numItems; i++)
        {
            Object item = bean.getExtensionList().get(i);
            if (canWriteExtension(item))
            {
                if (!extObjAdded)
                {
                    writer.name("extensions").beginObject();
                    extObjAdded = true;
                }
                
                writeExtension(writer, item);
            }
        }
        if (extObjAdded)
            writer.endObject();
    }
    
    
    public void writeAbstractSWEIdentifiableProperties(JsonWriter writer, AbstractSWEIdentifiable bean) throws IOException
    {
        writeAbstractSWEProperties(writer, bean);

        // identifier
        if (bean.isSetIdentifier())
            writer.name("identifier").value(bean.getIdentifier());

        // label
        if (bean.isSetLabel())
            writer.name("label").value(bean.getLabel());

        // description
        if (bean.isSetDescription())
            writer.name("description").value(bean.getDescription());
    }
    
    
    public void writeAbstractDataComponentProperties(JsonWriter writer, DataComponent bean) throws IOException
    {
        // definition
        if (bean.isSetDefinition())
            writer.name("definition").value(bean.getDefinition());
        
        writeAbstractSWEIdentifiableProperties(writer, bean);
        
        // updatable
        if (bean.isSetUpdatable())
            writer.name("updatable").value(bean.getUpdatable());

        // optional
        if (bean.isSetOptional())
            writer.name("optional").value(bean.getOptional());
    }
    
    
    public void writeAbstractSimpleComponentProperties(JsonWriter writer, SimpleComponent bean) throws IOException
    {
        writeAbstractDataComponentProperties(writer, bean);

        // referenceFrame
        if (bean.isSetReferenceFrame())
            writer.name("referenceFrame").value(bean.getReferenceFrame());

        // axisID
        if (bean.isSetAxisID())
            writer.name("axisID").value(bean.getAxisID());
        
        // quality
        if (!bean.getQualityList().isEmpty())
        {
            writer.name("quality").beginArray();
            
            for (OgcProperty<SimpleComponent> item: bean.getQualityList().getProperties())
            {
                if (item.hasValue() && !item.hasHref())
                    writeDataComponent(writer, item.getValue(), true);
                else if (item.hasHref())
                    writeLink(writer, item, true);
            }
            
            writer.endArray();
        }

        // nilValues
        if (bean.isSetNilValues())
        {
            writer.name("nilValues");
            OgcProperty<NilValues> nilValuesProp = bean.getNilValuesProperty();
            if (nilValuesProp.hasValue() && !nilValuesProp.hasHref())
                writeNilValues(writer, bean.getNilValues());
            else if (nilValuesProp.hasHref())
                writeLink(writer, nilValuesProp, true);
        }
    }
    
    
    public void writeDataRecordProperties(JsonWriter writer, DataRecord bean, boolean writeInlineValues) throws IOException
    {
        writeAbstractDataComponentProperties(writer, bean);
        
        // fields
        if (!bean.getFieldList().isEmpty())
        {
            writer.name("fields").beginArray();
            
            for (OgcProperty<DataComponent> item: bean.getFieldList().getProperties())
            {
                if (item.hasValue() && !item.hasHref())
                    writeDataComponent(writer, item.getValue(), writeInlineValues, item.getName());
                else if (item.hasHref())
                    writeLink(writer, item, true);
            }
            
            writer.endArray();
        }
    }
    
    
    public void writeVectorProperties(JsonWriter writer, Vector bean, boolean writeInlineValues) throws IOException
    {
        writeAbstractDataComponentProperties(writer, bean);

        // referenceFrame
        if (bean.isSetReferenceFrame())
            writer.name("referenceFrame").value(bean.getReferenceFrame());

        // localFrame
        if (bean.isSetLocalFrame())
            writer.name("localFrame").value(bean.getLocalFrame());

        // coordinates
        if (!bean.getCoordinateList().isEmpty())
        {
            writer.name("coordinates").beginArray();
            
            for (OgcProperty<ScalarComponent> item: bean.getCoordinateList().getProperties())
            {
                if (item.hasValue() && !item.hasHref())
                    writeDataComponent(writer, item.getValue(), writeInlineValues, item.getName());
                else if (item.hasHref())
                    writeLink(writer, item, true);
            }
            
            writer.endArray();
        }
    }
    
    
    public void writeDataChoiceProperties(JsonWriter writer, DataChoice bean, boolean writeInlineValues) throws IOException
    {
        writeAbstractDataComponentProperties(writer, bean);

        // choiceValue
        if (bean.isSetChoiceValue())
        {
            writer.name("choiceValue");
            writeDataComponent(writer, bean.getChoiceValue(), false);
        }

        // items
        if (!bean.getItemList().isEmpty())
        {
            writer.name("items").beginArray();
            
            for (OgcProperty<DataComponent> item: bean.getItemList().getProperties())
            {
                if (item.hasValue() && !item.hasHref())
                    writeDataComponent(writer, item.getValue(), writeInlineValues, item.getName());
                else if (item.hasHref())
                    writeLink(writer, item, true);
            }
            
            writer.endArray();
        }
    }
    
    
    public void writeDataArrayProperties(JsonWriter writer, DataArray bean, boolean writeInlineValues) throws IOException
    {
        writeAbstractDataComponentProperties(writer, bean);

        // elementCount
        writer.name("elementCount");
        OgcProperty<Count> elementCountProp = bean.getElementCountProperty();
        if (elementCountProp.hasValue() && !elementCountProp.hasHref())
            writeDataComponent(writer, bean.getElementCount(), true);
        else if (elementCountProp.hasHref())
            writeLink(writer, elementCountProp, true);

        // elementType
        writer.name("elementType");
        OgcProperty<DataComponent> elementTypeProp = bean.getElementTypeProperty();
        if (elementTypeProp.hasValue() && !elementTypeProp.hasHref())
            writeDataComponent(writer, bean.getElementType(), false, elementTypeProp.getName());
        else if (elementTypeProp.hasHref())
            writeLink(writer, elementTypeProp, true);
        
        if (writeInlineValues && bean.hasData())
        {
            // if not set, use text encoding by default
            if (!bean.isSetEncoding())
            {
                if (bean.getElementType() instanceof ScalarComponent)
                    bean.setEncoding(new TextEncodingImpl(",", " "));
                else
                    bean.setEncoding(new TextEncodingImpl());
            }
            
            // encoding
            writer.name("encoding");
            writeAbstractEncoding(writer, bean.getEncoding());
            
            // add encoded values object if needed
            if (!bean.isSetValues())
                bean.setValues(new EncodedValuesImpl());
            
            writer.name("values");
            writeEncodedValuesProperty(writer, bean, bean.getEncoding(), bean.getValues());
        }
    }
    
    
    public void writeMatrixProperties(JsonWriter writer, Matrix bean, boolean writeInlineValues) throws IOException
    {
        writeDataArrayProperties(writer, bean, writeInlineValues);

        // referenceFrame
        if (bean.isSetReferenceFrame())
            writer.name("referenceFrame").value(bean.getReferenceFrame());

        // localFrame
        if (bean.isSetLocalFrame())
            writer.name("localFrame").value(bean.getLocalFrame());
    }
    
    
    public void writeGeomProperties(JsonWriter writer, GeometryData bean, boolean writeInlineValues) throws IOException
    {
        writeAbstractDataComponentProperties(writer, bean);

        // referenceFrame
        if (bean.isSetReferenceFrame())
            writer.name("srs").value(bean.getReferenceFrame());
        
        // constraint
        if (bean.isSetConstraint())
        {
            OgcProperty<AllowedGeoms> constraintProp = bean.getConstraintProperty();
            if (constraintProp.hasValue())
            {
                writer.name("constraint");
                writeAllowedGeoms(writer, constraintProp.getValue());
            }
        }
        
        // value
        if (bean.hasData() && writeInlineValues)
        {
            writer.name("value");
            var dataWriter = new JsonDataWriterGson(writer);
            dataWriter.setDataComponents(bean);
            dataWriter.write(bean.getData());
        }
    }
    
    
    public void writeDataStream(JsonWriter writer, DataStream bean) throws IOException
    {
        writer.beginObject();
        writer.name("type").value("DataStream");
        writeDataStreamProperties(writer, bean);
        writer.endObject();
    }
    
    
    public void writeDataStreamProperties(JsonWriter writer, DataStream bean) throws IOException
    {
        writeAbstractSWEIdentifiableProperties(writer, bean);

        // elementCount
        if (bean.isSetElementCount())
        {
            writer.name("elementCount");
            writeDataComponent(writer, bean.getElementCount(), true);
        }

        // elementType
        writer.name("elementType");
        OgcProperty<DataComponent> elementTypeProp = bean.getElementTypeProperty();
        if (elementTypeProp.hasValue() && !elementTypeProp.hasHref())
            writeDataComponent(writer, bean.getElementType(), false, elementTypeProp.getName());
        else if (elementTypeProp.hasHref())
            writeLink(writer, elementTypeProp, true);

        // encoding
        writer.name("encoding");
        writeAbstractEncoding(writer, bean.getEncoding());

        // values
        writer.name("values");
        if (bean.isSetValues())
            writeEncodedValuesProperty(writer, bean, bean.getEncoding(), bean.getValues());
    }
    
    
    public void writeBooleanProperties(JsonWriter writer, Boolean bean, boolean writeInlineValues) throws IOException
    {
        writeAbstractSimpleComponentProperties(writer, bean);

        // value
        if (bean.isSetValue() && writeInlineValues)
            writer.name("value").value(bean.getValue());
    }

    
    public void writeTextProperties(JsonWriter writer, Text bean, boolean writeInlineValues) throws IOException
    {
        writeAbstractSimpleComponentProperties(writer, bean);

        // constraint
        if (bean.isSetConstraint())
        {
            OgcProperty<AllowedTokens> constraintProp = bean.getConstraintProperty();
            if (constraintProp.hasValue())
            {
                writer.name("constraint");
                writeAllowedTokens(writer, constraintProp.getValue());
            }
        }

        // value
        if (bean.isSetValue() && writeInlineValues)
            writer.name("value").value(bean.getValue());
    }
    
    
    protected void writeCountBaseProperties(JsonWriter writer, CountOrRange bean) throws IOException
    {
        writeAbstractSimpleComponentProperties(writer, bean);

        // constraint
        if (bean.isSetConstraint())
        {
            OgcProperty<AllowedValues> constraintProp = bean.getConstraintProperty();
            if (constraintProp.hasValue())
            {
                writer.name("constraint");
                writeAllowedValues(writer, constraintProp.getValue(), true);
            }
        }
    }
    
    
    public void writeCountProperties(JsonWriter writer, Count bean, boolean writeInlineValues) throws IOException
    {
        writeCountBaseProperties(writer, bean);

        // value
        if (bean.isSetValue() && writeInlineValues)
            writer.name("value").value(bean.getValue());
    }
    
    
    public void writeCountRangeProperties(JsonWriter writer, CountRange bean, boolean writeInlineValues) throws IOException
    {
        writeCountBaseProperties(writer, bean);

        // value
        if (bean.isSetValue() && writeInlineValues)
        {
            writer.name("value").beginArray();
            writeInline(writer, true);
            int[] val = bean.getValue();
            writer.value(val[0]);
            writer.value(val[1]);
            writer.endArray();
            writeInline(writer, false);
        }
    }
    
    
    protected void writeCategoryBaseProperties(JsonWriter writer, CategoryOrRange bean) throws IOException
    {
        writeAbstractSimpleComponentProperties(writer, bean);

        // codeSpace
        if (bean.isSetCodeSpace())
        {
            writer.name("codeSpace").value(bean.getCodeSpace());
        }
        
        // constraint
        if (bean.isSetConstraint())
        {
            OgcProperty<AllowedTokens> constraintProp = bean.getConstraintProperty();
            if (constraintProp.hasValue())
            {
                writer.name("constraint");
                writeAllowedTokens(writer, constraintProp.getValue());
            }
        }
    }
    
    
    public void writeCategoryProperties(JsonWriter writer, Category bean, boolean writeInlineValues) throws IOException
    {
        writeCategoryBaseProperties(writer, bean);

        // value
        if (bean.isSetValue() && writeInlineValues)
            writer.name("value").value(bean.getValue());
    }
    
    
    public void writeCategoryRangeProperties(JsonWriter writer, CategoryRange bean, boolean writeInlineValues) throws IOException
    {
        writeCategoryBaseProperties(writer, bean);

        // value
        if (bean.isSetValue() && writeInlineValues)
        {
            writer.name("value").beginArray();
            writeInline(writer, true);
            String[] val = bean.getValue();
            writer.value(val[0]);
            writer.value(val[1]);
            writer.endArray();
            writeInline(writer, false);
        }
    }
    
    
    protected void writeQuantityBaseProperties(JsonWriter writer, QuantityOrRange bean) throws IOException
    {
        writeAbstractSimpleComponentProperties(writer, bean);
        
        // uom
        writer.name("uom");
        writeUnitReference(writer, bean.getUom());

        // constraint
        if (bean.isSetConstraint())
        {
            OgcProperty<AllowedValues> constraintProp = bean.getConstraintProperty();
            if (constraintProp.hasValue())
            {
                writer.name("constraint");
                writeAllowedValues(writer, constraintProp.getValue(), false);
            }
        }
    }
    
    
    public void writeQuantityProperties(JsonWriter writer, Quantity bean, boolean writeInlineValues) throws IOException
    {
        writeQuantityBaseProperties(writer, bean);

        // value
        if (bean.isSetValue() && writeInlineValues)
        {
            writer.name("value");
            writeNumberOrSpecialValue(writer, bean.getValue());
        }
    }
    
    
    public void writeQuantityRangeProperties(JsonWriter writer, QuantityRange bean, boolean writeInlineValues) throws IOException
    {
        writeQuantityBaseProperties(writer, bean);

        // value
        if (bean.isSetValue() && writeInlineValues)
        {
            writer.name("value").beginArray();
            writeInline(writer, true);
            double[] val = bean.getValue();
            writeNumberOrSpecialValue(writer, val[0]);
            writeNumberOrSpecialValue(writer, val[1]);
            writer.endArray();
            writeInline(writer, false);
        }
    }
    
    
    protected void writeTimeBaseProperties(JsonWriter writer, TimeOrRange bean) throws IOException
    {
        writeAbstractSimpleComponentProperties(writer, bean);
        
        // referenceTime
        if (bean.isSetReferenceTime())
            writer.name("referenceTime").value(getStringValue(bean.getReferenceTime()));

        // localFrame
        if (bean.isSetLocalFrame())
            writer.name("localFrame").value(bean.getLocalFrame());
        
        // uom
        writer.name("uom");
        writeUnitReference(writer, bean.getUom());

        // constraint
        if (bean.isSetConstraint())
        {
            OgcProperty<AllowedTimes> constraintProp = bean.getConstraintProperty();
            if (constraintProp.hasValue())
            {
                writer.name("constraint");
                writeAllowedTimes(writer, constraintProp.getValue());
            }
        }
    }
    
    
    public void writeTimeProperties(JsonWriter writer, Time bean, boolean writeInlineValues) throws IOException
    {
        writeTimeBaseProperties(writer, bean);

        // value
        if (bean.isSetValue() && writeInlineValues)
        {
            writer.name("value");
            writeTimeVal(writer, bean.getValue());
        }
    }
    
    
    public void writeTimeRangeProperties(JsonWriter writer, TimeRange bean, boolean writeInlineValues) throws IOException
    {
        writeTimeBaseProperties(writer, bean);

        // value
        if (bean.isSetValue() && writeInlineValues)
        {
            writer.name("value").beginArray();
            var range = bean.getValue();
            writeTimeVal(writer, range[0]);
            writeTimeVal(writer, range[1]);
            writer.endArray();
        }
    }
    
    
    public void writeNilValues(JsonWriter writer, NilValues bean) throws IOException
    {
        writer.beginArray();
        for (var nilVal: bean.getNilValueList())
            writeNilValue(writer, nilVal);
        writer.endArray();
    }
    
    
    public void writeAllowedTokens(JsonWriter writer, AllowedTokens bean) throws IOException
    {
        writer.beginObject();
        writeAllowedTokensProperties(writer, bean);
        writer.endObject();
    }
    
    
    public void writeAllowedTokensProperties(JsonWriter writer, AllowedTokens bean) throws IOException
    {
        writeAbstractSWEProperties(writer, bean);

        // values
        if (bean.getNumValues() > 0)
        {
            writer.name("values").beginArray();
            for (String val: bean.getValueList())
                writer.value(val);
            writer.endArray();
        }

        // pattern
        if (bean.isSetPattern())
            writer.name("pattern").value(bean.getPattern());
    }
    
    
    public void writeAllowedValues(JsonWriter writer, AllowedValues bean, boolean writeIntegers) throws IOException
    {
        writer.beginObject();
        writeAllowedValuesProperties(writer, bean, writeIntegers);
        writer.endObject();
    }
    
    
    public void writeAllowedValuesProperties(JsonWriter writer, AllowedValues bean, boolean writeIntegers) throws IOException
    {
        writeAbstractSWEProperties(writer, bean);

        // values
        if (bean.getNumValues() > 0)
        {
            writer.name("values").beginArray();
            writeInline(writer, true);
            for (double val: bean.getValueList())
                writeNumberOrSpecialValue(writer, val);
            writer.endArray();
            writeInline(writer, false);
        }

        // intervals
        if (bean.getNumIntervals() > 0)
        {
            writer.name("intervals").beginArray();
            for (double[] range: bean.getIntervalList())
            {
                writer.beginArray();
                writeInline(writer, true);
                writeNumberOrSpecialValue(writer, range[0]);
                writeNumberOrSpecialValue(writer, range[1]);
                writer.endArray();
                writeInline(writer, false);
            }
            writer.endArray();
        }

        // significantFigures
        if (bean.isSetSignificantFigures())
            writer.name("significantFigures").value(bean.getSignificantFigures());
    }
    
    
    public void writeAllowedTimes(JsonWriter writer, AllowedTimes bean) throws IOException
    {
        writer.beginObject();
        writeAllowedTimesProperties(writer, bean);
        writer.endObject();
    }
    
    
    public void writeAllowedTimesProperties(JsonWriter writer, AllowedTimes bean) throws IOException
    {
        writeAbstractSWEProperties(writer, bean);

        // values
        if (bean.getNumValues() > 0)
        {
            writer.name("values").beginArray();
            for (var val: bean.getValueList())
                writeTimeVal(writer, val);
            writer.endArray();
        }

        // intervals
        if (bean.getNumIntervals() > 0)
        {
            writer.name("intervals").beginArray();
            for (var range: bean.getIntervalList())
            {
                writer.beginArray();
                writeTimeVal(writer, range[0]);
                writeTimeVal(writer, range[1]);
                writer.endArray();
            }
            writer.endArray();
        }

        // significantFigures
        if (bean.isSetSignificantFigures())
            writer.name("significantFigures").value(bean.getSignificantFigures());
    }
    
    
    public void writeAllowedGeoms(JsonWriter writer, AllowedGeoms bean) throws IOException
    {
        writer.beginObject();
        writeAllowedGeomsProperties(writer, bean);
        writer.endObject();
    }
    
    
    public void writeAllowedGeomsProperties(JsonWriter writer, AllowedGeoms bean) throws IOException
    {
        writeAbstractSWEProperties(writer, bean);
        
        // geom list
        if (bean.getNumGeomTypes() > 0)
        {
            writer.name("geomTypes").beginArray();
            for (GeomType val: bean.getGeomList())
                writer.value(val.toString());
            writer.endArray();
        }
    }
    
    
    public void writeUnitReference(JsonWriter writer, UnitReference bean) throws IOException
    {
        writer.beginObject();
        writeUnitReferenceProperties(writer, bean);
        writer.endObject();
    }
    
    
    public void writeUnitReferenceProperties(JsonWriter writer, UnitReference bean) throws IOException
    {
        // code
        if (bean.isSetCode())
            writer.name("code").value(bean.getCode());
        
        else if (bean.hasHref())
            writer.name("href").value(bean.getHref());
    }
    
    
    public void writeNilValue(JsonWriter writer, NilValue bean) throws IOException
    {
        writer.beginObject();
        writer.name("reason").value(bean.getReason());
        writer.name("value").value(bean.getValue());
        writer.endObject();
    }
    
    
    public void writeEncodedValuesProperty(JsonWriter writer, AbstractSWEIdentifiable blockComponent, DataEncoding encoding, EncodedValues bean) throws IOException
    {
        if (bean.hasHref())
        {
            writeLink(writer, bean, true);
        }
        else
        {
            writer.beginArray();
            
            if (blockComponent instanceof DataArray)
            {
                var dataArray = (DataArray)blockComponent;
                var dataWriter = new JsonArrayDataWriterGson(writer);
                dataWriter.setDataComponents(dataArray.getElementType());
                
                int arraySize = dataArray.getComponentCount();
                for (int i = 0; i < arraySize; i++)
                    dataWriter.write(dataArray.getComponent(i).getData());
            }
            else if (blockComponent instanceof DataStream)
            {
                var dataStream = (DataStream)blockComponent;
                var dataWriter = new JsonArrayDataWriterGson(writer);
                dataWriter.setDataComponents(dataStream.getElementType());
                var it = bean.getData().blockIterator();
                while(it.hasNext())
                    dataWriter.write(it.next());
            }
            
            writer.endArray();
        }
    }


    /**
     * Dispatcher method for writing classes derived from AbstractDataComponent
     */
    public void writeDataComponent(JsonWriter writer, DataComponent bean, boolean writeInlineValues) throws IOException
    {
        writeDataComponent(writer, bean, writeInlineValues, null);
    }
    
    
    public void writeDataComponent(JsonWriter writer, DataComponent bean, boolean writeInlineValues, String name) throws IOException
    {
        writer.beginObject();
        
        if (bean instanceof DataRecord)
        {
            writeTypeAndName(writer, "DataRecord", name);
            writeDataRecordProperties(writer, (DataRecord)bean, writeInlineValues);
        }
        else if (bean instanceof Vector)
        {
            writeTypeAndName(writer, "Vector", name);
            writeVectorProperties(writer, (Vector)bean, writeInlineValues);
        }
        else if (bean instanceof Matrix)
        {
            writeTypeAndName(writer, "Matrix", name);
            writeMatrixProperties(writer, (Matrix)bean, writeInlineValues);
        }
        else if (bean instanceof DataArray)
        {
            writeTypeAndName(writer, "DataArray", name);
            writeDataArrayProperties(writer, (DataArray)bean, writeInlineValues);
        }
        else if (bean instanceof GeometryData)
        {
            writeTypeAndName(writer, "Geometry", name);
            writeGeomProperties(writer, (GeometryData)bean, writeInlineValues);
        }
        else if (bean instanceof DataChoice)
        {
            writeTypeAndName(writer, "DataChoice", name);
            writeDataChoiceProperties(writer, (DataChoice)bean, writeInlineValues);
        }
        else if (bean instanceof Count)
        {
            writeTypeAndName(writer, "Count", name);
            writeCountProperties(writer, (Count)bean, writeInlineValues);
        }
        else if (bean instanceof CountRange)
        {
            writeTypeAndName(writer, "CountRange", name);
            writeCountRangeProperties(writer, (CountRange)bean, writeInlineValues);
        }
        else if (bean instanceof Category)
        {
            writeTypeAndName(writer, "Category", name);
            writeCategoryProperties(writer, (Category)bean, writeInlineValues);
        }
        else if (bean instanceof CategoryRange)
        {
            writeTypeAndName(writer, "CategoryRange", name);
            writeCategoryRangeProperties(writer, (CategoryRange)bean, writeInlineValues);
        }
        else if (bean instanceof Quantity)
        {
            writeTypeAndName(writer, "Quantity", name);
            writeQuantityProperties(writer, (Quantity)bean, writeInlineValues);
        }
        else if (bean instanceof QuantityRange)
        {
            writeTypeAndName(writer, "QuantityRange", name);
            writeQuantityRangeProperties(writer, (QuantityRange)bean, writeInlineValues);
        }
        else if (bean instanceof Time)
        {
            writeTypeAndName(writer, "Time", name);
            writeTimeProperties(writer, (Time)bean, writeInlineValues);
        }
        else if (bean instanceof TimeRange)
        {
            writeTypeAndName(writer, "TimeRange", name);
            writeTimeRangeProperties(writer, (TimeRange)bean, writeInlineValues);
        }
        else if (bean instanceof Boolean)
        {
            writeTypeAndName(writer, "Boolean", name);
            writeBooleanProperties(writer, (Boolean)bean, writeInlineValues);
        }
        else if (bean instanceof Text)
        {
            writeTypeAndName(writer, "Text", name);
            writeTextProperties(writer, (Text)bean, writeInlineValues);
        }
        
        writer.endObject();
    }
    
    
    public void writeTypeAndName(JsonWriter writer, String type, String name) throws IOException
    {
        writer.name("type").value(type);
        if (name != null)
            writer.name("name").value(name);
    }


    /**
     * Dispatcher method for writing classes derived from AbstractEncoding
     */
    public void writeAbstractEncoding(JsonWriter writer, DataEncoding bean) throws IOException
    {
        writer.beginObject();
        
        if (bean instanceof TextEncoding)
        {
            writer.name("type").value("TextEncoding");
            writeTextEncodingProperties(writer, (TextEncoding)bean);
        }
        else if (bean instanceof JSONEncoding)
        {
            writer.name("type").value("JSONEncoding");
            writeJsonEncodingProperties(writer, (JSONEncoding)bean);
        }
        else if (bean instanceof XMLEncoding)
        {
            writer.name("type").value("XMLEncoding");
            writeXmlEncodingProperties(writer, (XMLEncoding)bean);
        }
        else if (bean instanceof BinaryEncoding)
        {
            writer.name("type").value("BinaryEncoding");
            writeBinaryEncodingProperties(writer, (BinaryEncoding)bean);
        }
        
        writer.endObject();
    }
    
    
    public void writeTextEncodingProperties(JsonWriter writer, TextEncoding bean) throws IOException
    {
        writer.name("collapseWhiteSpaces").value(bean.getCollapseWhiteSpaces());
        writer.name("decimalSeparator").value(bean.getDecimalSeparator());
        writer.name("tokenSeparator").value(bean.getTokenSeparator());
        writer.name("blockSeparator").value(bean.getBlockSeparator());
    }
    
    
    public void writeJsonEncodingProperties(JsonWriter writer, JSONEncoding bean) throws IOException
    {
        
    }
    
    
    public void writeXmlEncodingProperties(JsonWriter writer, XMLEncoding bean) throws IOException
    {
        
    }
    
    
    public void writeBinaryEncodingProperties(JsonWriter writer, BinaryEncoding bean) throws IOException
    {
        writer.name("byteOrder").value(bean.getByteOrder().toString());
        writer.name("byteEncoding").value(bean.getByteEncoding().toString());
        
        if (bean.getNumMembers() > 0)
        {
            writer.name("members").beginArray();
            for (var member: bean.getMemberList())
            {
                writer.beginObject();
                if (member instanceof BinaryComponent)
                {
                    writer.name("type").value("Component");
                    writer.name("ref").value(member.getRef());
                    
                    var comp = (BinaryComponent)member;
                    writer.name("dataType").value(comp.getDataType());

                    if (comp.isSetBitLength())
                        writer.name("bitLength").value(comp.getBitLength());

                    if (comp.isSetByteLength())
                        writer.name("byteLength").value(comp.getByteLength());

                    if (comp.isSetSignificantBits())
                        writer.name("significantBits").value(comp.getSignificantBits());

                    if (comp.isSetEncryption())
                        writer.name("encryption").value(comp.getEncryption());
                }
                else if (member instanceof BinaryBlock)
                {
                    writer.name("type").value("Block");
                    writer.name("ref").value(member.getRef());
                    
                    var block = (BinaryBlock)member;
                    
                    if (block.isSetByteLength())
                        writer.name("byteLength").value(block.getByteLength());
                    
                    if (block.isSetPaddingBytesBefore())
                        writer.name("paddingBytes-before").value(block.getPaddingBytesBefore());
                    
                    if (block.isSetPaddingBytesAfter())
                        writer.name("paddingBytes-after").value(block.getPaddingBytesAfter());

                    if (block.isSetEncryption())
                        writer.name("encryption").value(block.getEncryption());

                    if (block.isSetCompression())
                        writer.name("compression").value(block.getCompression());
                }
                writer.endObject();
            }
            writer.endArray();
        }
    }
    
    
    public void writeExtension(JsonWriter writer, Object obj) throws IOException
    {
        // we do nothing by default
        // sub-classes can override to implement some extensions
    }
    
    
    protected void writeTimeVal(JsonWriter writer, DateTimeOrDouble val) throws IOException
    {
        if (val.isDateTime())
        {
            var dateTime = val.getDateTime();
            if (OffsetDateTime.MAX.equals(dateTime))
                writer.value(PLUS_INFINITY);
            else if (OffsetDateTime.MIN.equals(dateTime))
                writer.value(MINUS_INFINITY);
            else            
             writer.value(dateTime.format(DateTimeFormat.ISO_DATE_OR_TIME_FORMAT));
        }
        else
            writeNumberOrSpecialValue(writer, val.getDecimalTime());
    }
    
    
    protected void writeNumberOrSpecialValue(JsonWriter writer, double val) throws IOException
    {
        if (Double.isNaN(val))
            writer.value(NAN);
        else if (val == Double.POSITIVE_INFINITY)
            writer.value(PLUS_INFINITY);
        else if (val == Double.NEGATIVE_INFINITY)
            writer.value(MINUS_INFINITY);
        else
            writer.value(val);
    }
    
    
    protected void writeLink(JsonWriter writer, OgcProperty<?> prop, boolean includeType) throws IOException
    {
        writer.beginObject();
        String val;
        
        //if (includeType)
        //    writer.name("type").value("Link");
        
        val = prop.getName();
        if (val != null)
            writer.name("name").value(val);
        
        val = prop.getHref();
        if (val != null)
            writer.name("href").value(val);
        
        val = prop.getTitle();
        if (val != null)
            writer.name("title").value(val);
        
        val = prop.getRole();
        if (val != null)
            writer.name("role").value(val);
        
        val = prop.getArcRole();
        if (val != null)
            writer.name("arcrole").value(val);
            
            writer.endObject();
    }
    
    
    protected void writeInline(JsonWriter writer, boolean inline)
    {
        if (writer instanceof JsonInliningWriter)
            ((JsonInliningWriter)writer).writeInline(inline);
    }


    public Factory getFactory()
    {
        return factory;
    }
}
