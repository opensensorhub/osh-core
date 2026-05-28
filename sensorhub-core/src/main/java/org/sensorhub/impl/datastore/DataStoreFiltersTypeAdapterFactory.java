/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2019 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.datastore;

import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;
import java.util.function.Predicate;
import org.sensorhub.api.datastore.IQueryFilter;
import org.sensorhub.api.datastore.SpatialFilter;
import org.sensorhub.api.datastore.TemporalFilter;
import org.sensorhub.api.datastore.VersionFilter;
import org.sensorhub.api.datastore.func.JavascriptPredicate;
import org.vast.util.DateTimeFormat;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.datastore.FullTextFilter;
import org.sensorhub.api.datastore.SpatialFilter.SpatialOp;
import com.google.common.collect.Range;
import com.google.common.collect.Sets;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonIOException;
import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;


/**
 * <p>
 * Gson type adapters to implement clean serializations of Datastore API
 * filter objects.
 * </p>
 *
 * @author Alex Robin
 * @date Oct 8, 2020
 */
public class DataStoreFiltersTypeAdapterFactory implements TypeAdapterFactory
{       
    
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type)
    {
          Class<T> rawType = (Class<T>) type.getRawType();
          
          if (rawType == BigId.class)
              return (TypeAdapter<T>)new BigIdTypeAdapter().nullSafe();
          else if (rawType == Instant.class)
              return (TypeAdapter<T>)new InstantTypeAdapter().nullSafe();
          else if (rawType == Duration.class)
              return (TypeAdapter<T>)new DurationTypeAdapter().nullSafe();
          else if (rawType == Predicate.class)
              return (TypeAdapter<T>)new PredicateTypeAdapter().nullSafe();
          else if (rawType == Range.class && (type.getType() instanceof ParameterizedType))
              return (TypeAdapter<T>)new RangeTypeAdapter(gson, type).nullSafe();
          else if (rawType == VersionFilter.class)
              return (TypeAdapter<T>)new VersionFilterTypeAdapter(gson).nullSafe();
          else if (rawType == TemporalFilter.class)
              return (TypeAdapter<T>)new TemporalFilterTypeAdapter(gson).nullSafe();
          else if (rawType == Geometry.class)
              return (TypeAdapter<T>)new GeometryTypeAdapter().nullSafe();
          else if (rawType == SpatialFilter.class)
              return (TypeAdapter<T>)new SpatialFilterTypeAdapter(gson).nullSafe();
          else if (IQueryFilter.class.isAssignableFrom(rawType))
              return (TypeAdapter<T>)new QueryFilterTypeAdapter(gson, this, type).nullSafe();
          
          return null;
    }
    
    
    public static class BigIdTypeAdapter extends TypeAdapter<BigId>
    {
        @Override
        public void write(JsonWriter writer, BigId id) throws IOException
        {
            var idStr = BigId.toString32(id);
            writer.value(idStr);
        }

        @Override
        public BigId read(JsonReader reader) throws IOException
        {
            var idStr = reader.nextString();
            return BigId.fromString32(idStr);
        }
    }
    
    
    public static class InstantTypeAdapter extends TypeAdapter<Instant>
    {
        static DateTimeFormatter formatter = DateTimeFormat.ISO_DATE_OR_TIME_FORMAT;
        
        @Override
        public void write(JsonWriter writer, Instant instant) throws IOException
        {
            String timeStr = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC).format(formatter);
            writer.value(timeStr.replace("T00:00:00", ""));
        }

        @Override
        public Instant read(JsonReader reader) throws IOException
        {
            var timeStr = reader.nextString();
            return OffsetDateTime.parse(timeStr, formatter).toInstant();
        }
    }
    
    
    public static class DurationTypeAdapter extends TypeAdapter<Duration>
    {
        @Override
        public void write(JsonWriter writer, Duration d) throws IOException
        {
            if (d.toSeconds() < 60)
                writer.value(d.toMillis());
            else
                writer.value(d.toString());
        }

        @Override
        public Duration read(JsonReader reader) throws IOException
        {
            if (reader.peek() == JsonToken.NUMBER)
                return Duration.ofMillis(reader.nextInt());
            else
                return Duration.parse(reader.nextString());
        }
    }
    
    
    public static class PredicateTypeAdapter extends TypeAdapter<Predicate<?>>
    {
        @Override
        public void write(JsonWriter writer, Predicate<?> predicate) throws IOException
        {
            if (predicate instanceof JavascriptPredicate)
                writer.value(((JavascriptPredicate<?>)predicate).getCode());
        }

        @Override
        public Predicate<?> read(JsonReader reader) throws IOException
        {
            var str = reader.nextString();
            return new JavascriptPredicate<>(str);
        }
        
    }

    
    public static class RangeTypeAdapter<T extends Comparable<T>> extends TypeAdapter<Range<T>>
    {
        final TypeAdapter<T> rangeEltType;
        
        @SuppressWarnings("unchecked")
        RangeTypeAdapter(Gson gson, TypeToken<T> type)
        {
            var eltType = ((ParameterizedType)type.getType()).getActualTypeArguments()[0];
            this.rangeEltType = (TypeAdapter<T>)gson.getAdapter(TypeToken.get(eltType));
        }
        
        @Override
        public void write(JsonWriter writer, Range<T> range) throws IOException
        {
            if (range.lowerEndpoint() instanceof Integer)
            {
                if ((Integer)range.lowerEndpoint() == Integer.MAX_VALUE &&
                    (Integer)range.upperEndpoint() == Integer.MAX_VALUE)
                {
                    writer.beginObject()
                          .name("indeterminate").value("latest")
                          .endObject();
                    return;
                }
            }
            
            writer.beginArray();
            rangeEltType.write(writer, range.lowerEndpoint());
            rangeEltType.write(writer, range.upperEndpoint());
            writer.endArray();
        }

        @Override
        public Range<T> read(JsonReader reader) throws IOException
        {
            reader.beginArray();
            var min = rangeEltType.read(reader);
            var max = rangeEltType.read(reader);
            reader.endArray();
            
            return Range.closed(min, max);
        }
    }


    @SuppressWarnings({ "unchecked" })
    public class TemporalFilterTypeAdapter extends TypeAdapter<TemporalFilter>
    {
        private final TypeAdapter<Range<Instant>> timeRangeType;
        
        TemporalFilterTypeAdapter(Gson gson)
        {
            this.timeRangeType = (TypeAdapter<Range<Instant>>) gson.getAdapter(
                TypeToken.getParameterized(Range.class, Instant.class));
        }
        
        @Override
        public void write(JsonWriter writer, TemporalFilter filter) throws IOException
        {
            writer.beginObject();
            if (filter.isLatestTime())
                writer.name("indeterminate").value("latest");
            else if (filter.isCurrentTime())
                writer.name("indeterminate").value("current");
            else
            {
                writer.name("during");
                timeRangeType.write(writer, filter.getRange());
            }
            writer.endObject();
        }
    
        @Override
        public TemporalFilter read(JsonReader reader) throws IOException
        {
            var builder = new TemporalFilter.Builder();
            
            reader.beginObject();
            while (reader.hasNext())
            {
                String name = reader.nextName();
                if ("indeterminate".equals(name))
                {
                    var val = reader.nextString();
                    if ("latest".equals(val))
                        builder.withLatestTime();
                    else if ("current".equals(val))
                        builder.withCurrentTime();
                    else
                        throw new JsonIOException("Invalid indeterminate value: " + val);
                }
                else if ("during".equals(name))
                {
                    Range<Instant> timeRange = timeRangeType.read(reader);
                    builder.withRange(timeRange);
                }
                else // ignore any other property
                    reader.skipValue();
            }
            reader.endObject();
            
            return builder.build();
        }
    }


    @SuppressWarnings({ "unchecked" })
    public class VersionFilterTypeAdapter extends TypeAdapter<VersionFilter>
    {
        private final TypeAdapter<Range<Integer>> rangeType;
        
        VersionFilterTypeAdapter(Gson gson)
        {
            this.rangeType = (TypeAdapter<Range<Integer>>) gson.getAdapter(
                TypeToken.getParameterized(Range.class, Integer.class));
        }
        
        @Override
        public void write(JsonWriter writer, VersionFilter filter) throws IOException
        {
            writer.beginObject();
            if (filter.isCurrentVersion())
                writer.name("indeterminate").value("current");
            else
            {
                writer.name("range");
                rangeType.write(writer, filter.getRange());
            }
            writer.endObject();
        }
    
        @Override
        public VersionFilter read(JsonReader reader) throws IOException
        {
            var builder = new VersionFilter.Builder();
            
            reader.beginObject();
            while (reader.hasNext())
            {
                String name = reader.nextName();
                if ("indeterminate".equals(name))
                {
                    var val = reader.nextString();
                    if ("current".equals(val))
                        builder.withCurrentVersion();
                    else
                        throw new JsonIOException("Invalid indeterminate value: " + val);
                }
                else if ("range".equals(name))
                {
                    Range<Integer> range = rangeType.read(reader);
                    builder.withRange(range);
                }
                else // ignore any other property
                    reader.skipValue();
            }
            reader.endObject();
            
            return builder.build();
        }
    }
    
    
    public static class GeometryTypeAdapter extends TypeAdapter<Geometry>
    {
        GeometryFactory geomFac = new GeometryFactory();
        
        @Override
        public void write(JsonWriter writer, Geometry geom) throws IOException
        {
            writer.beginObject();
            writer.name("type").value(geom.getGeometryType());
            writer.name("coordinates");
            if (geom.getNumPoints() > 1)
                writer.beginArray();
            for (Coordinate coord: geom.getCoordinates())
            {
                writer.beginArray();
                writer.value(coord.x);
                writer.value(coord.y);
                if (!Double.isNaN(coord.z))
                    writer.value(coord.z);
                writer.endArray();
            }
            if (geom.getNumPoints() > 1)
                writer.endArray();
            writer.endObject();
        }
        
        private Coordinate readCoordinate(JsonReader reader) throws IOException
        {
            Coordinate coord = new Coordinate();
            int i = 0;
            while (reader.hasNext())
            {
                var val = reader.nextDouble();
                coord.setOrdinate(i++, val);
            }
            return coord;
        }

        @Override
        public Geometry read(JsonReader reader) throws IOException
        {
            Geometry geom = null;
            String geomType = null;
            
            reader.beginObject();
            while (reader.hasNext())
            {
                String name = reader.nextName();
                
                if ("type".equals(name))
                {
                    geomType = reader.nextString();
                }
                else if ("coordinates".equals(name))
                {
                    var coords = new ArrayList<Coordinate>();
                    
                    // read coordinate array or array of array
                    reader.beginArray();
                    if (reader.peek() == JsonToken.BEGIN_ARRAY)
                    {
                        while (reader.hasNext())
                        {
                            reader.beginArray();
                            coords.add(readCoordinate(reader));
                            reader.endArray();                            
                        }
                    }
                    else
                        coords.add(readCoordinate(reader));
                    reader.endArray();
                    
                    if ("Point".equals(geomType))
                        geom = geomFac.createPoint(coords.get(0));
                    else if ("LineString".equals(geomType))                        
                        geom = geomFac.createLineString(coords.toArray(new Coordinate[0]));
                    else if ("Polygon".equals(geomType))                        
                        geom = geomFac.createPolygon(coords.toArray(new Coordinate[0]));
                    else
                        throw new JsonIOException("Invalid or unsupported geometry type: " + geomType);
                }
                else // ignore any other property
                    reader.skipValue();
            }
            
            reader.endObject();
            
            return geom;
        }
    }
    
    
    public static class SpatialFilterTypeAdapter extends TypeAdapter<SpatialFilter>
    {
        private final TypeAdapter<Geometry> geomType;
        
        SpatialFilterTypeAdapter(Gson gson)
        {
            this.geomType = gson.getAdapter(Geometry.class);
        }
        
        @Override
        public void write(JsonWriter writer, SpatialFilter filter) throws IOException
        {
            writer.beginObject();
            writer.name(filter.getOperator().toString().toLowerCase());
            
            writer.beginObject();
            if (filter.getOperator() == SpatialOp.WITHIN_DISTANCE)
            {
                writer.name("center");
                geomType.write(writer, filter.getCenter());
                writer.name("distance").value(filter.getDistance());
            }
            else
            {
                writer.name("roi");
                geomType.write(writer, filter.getRoi());
            }
            writer.endObject();
            
            writer.endObject();
        }

        @Override
        public SpatialFilter read(JsonReader reader) throws IOException
        {
            Point center = null;
            double distance = Double.NaN;
            var builder = new SpatialFilter.Builder();
            
            reader.beginObject();
            String spatialOp = reader.nextName().toUpperCase();
            builder.withOperator(SpatialOp.valueOf(spatialOp));
            
            reader.beginObject();
            while (reader.hasNext())
            {
                String name = reader.nextName();
                if ("roi".equals(name))
                    builder.withRoi(geomType.read(reader));
                else if ("distance".equals(name))
                    distance = reader.nextDouble();
                else if ("center".equals(name))
                    center = (Point)geomType.read(reader);
                else // ignore any other property
                    reader.skipValue();
            }                        
            reader.endObject();
            reader.endObject();
            
            if (center != null)
                builder.withDistanceToPoint(center, distance);
            
            return builder.build();
        }
    }
    
    
    public static class TextFilterTypeAdapter extends TypeAdapter<FullTextFilter>
    {
        public void write(JsonWriter writer, FullTextFilter filter) throws IOException
        {
            writer.beginArray();
            for (var s: filter.getKeywords())
                writer.value(s);
            writer.endArray();
        }

        public FullTextFilter read(JsonReader reader) throws IOException
        {
            return null;
        }
    }
    
    
    public static class QueryFilterTypeAdapter<T extends IQueryFilter> extends TypeAdapter<T>
    {
        static String OFFSET_FIELD = "offset";
        static String LIMIT_FIELD = "limit";
        
        final TypeAdapter<JsonElement> jsonEltAdapter;
        final TypeAdapter<T> filterType;
                     
        QueryFilterTypeAdapter(Gson gson, DataStoreFiltersTypeAdapterFactory factory, TypeToken<T> type)
        {
            this.jsonEltAdapter = gson.getAdapter(JsonElement.class);
            this.filterType = (TypeAdapter<T>)gson.getDelegateAdapter(factory, type);
        }
        
        public void write(JsonWriter writer, T filter) throws IOException
        {
            JsonObject jsObj = (JsonObject)filterType.toJsonTree(filter);
            
            if (filter.getOffset() <= 0)
                jsObj.remove(OFFSET_FIELD);
                        
            if (filter.getLimit() == Long.MAX_VALUE)
                jsObj.remove(LIMIT_FIELD);
            
            jsonEltAdapter.write(new FormattingJsonWriter(writer), jsObj);
        }

        public T read(JsonReader reader) throws IOException
        {
            return filterType.read(reader);
        }
    }
    
    
    public static class FormattingJsonWriter extends JsonWriter
    {
        static Set<String> inlineArrayFields = Sets.newHashSet(
            "coordinates", "internalIDs");
        
        JsonWriter delegate;
        String lastName;
        boolean nestedArray;
        
        public FormattingJsonWriter(JsonWriter delegate)
        {
            super(new StringWriter());
            this.delegate = delegate;
        }

        public boolean isLenient()
        {
            return delegate.isLenient();
        }

        public JsonWriter beginArray() throws IOException
        {
            if (nestedArray)
                delegate.setIndent("  ");
            delegate.beginArray();
            nestedArray = true;
            if (inlineArrayFields.contains(lastName))
                delegate.setIndent("");
            return this;
        }

        public JsonWriter endArray() throws IOException
        {
            delegate.endArray();
            delegate.setIndent("  ");
            return this;
        }

        public JsonWriter beginObject() throws IOException
        {
            delegate.beginObject();
            return this;
        }

        public JsonWriter endObject() throws IOException
        {
            delegate.endObject();
            return this;
        }

        public JsonWriter name(String name) throws IOException
        {
            this.lastName = name;
            delegate.name(name);
            return this;
        }

        public JsonWriter value(String value) throws IOException
        {
            delegate.value(value);
            return this;
        }

        public JsonWriter jsonValue(String value) throws IOException
        {
            delegate.jsonValue(value);
            return this;
        }

        public JsonWriter nullValue() throws IOException
        {
            delegate.nullValue();
            return this;
        }

        public JsonWriter value(boolean value) throws IOException
        {
            delegate.value(value);
            return this;
        }

        public JsonWriter value(Boolean value) throws IOException
        {
            delegate.value(value);
            return this;
        }

        public JsonWriter value(double value) throws IOException
        {
            delegate.value(value);
            return this;
        }

        public JsonWriter value(long value) throws IOException
        {
            delegate.value(value);
            return this;
        }

        public JsonWriter value(Number value) throws IOException
        {
            delegate.value(value);
            return this;
        }

        public void flush() throws IOException
        {
            delegate.flush();
        }

        public void close() throws IOException
        {
            delegate.close();
        }
    }
    
    
    public static class FieldNamingStrategy implements com.google.gson.FieldNamingStrategy
    {
        static HashMap<String, String> serializedNames = new HashMap<>();
        
        public FieldNamingStrategy()
        {
            serializedNames.put("systemFilter", "withSystems");
            serializedNames.put("obsFilter", "withObservations");
            serializedNames.put("dataStreamFilter", "withDatastreams");
            serializedNames.put("foiFilter", "withFois");
            serializedNames.put("sampledFeatureFilter", "withSampledFeatures");
            serializedNames.put("parentFilter", "withParents");
            serializedNames.put("memberFilter", "withMembers");
            serializedNames.put("valuePredicate", "predicate");
        }
        
        @Override
        public String translateName(Field f)
        {
            String jsonName = serializedNames.get(f.getName());
            if (jsonName != null && IQueryFilter.class.isAssignableFrom(f.getDeclaringClass()))
                return jsonName;
            else
                return f.getName();
        }
        
    }
}