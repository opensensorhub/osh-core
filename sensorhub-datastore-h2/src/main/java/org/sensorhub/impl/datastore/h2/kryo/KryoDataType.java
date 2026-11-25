/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.datastore.h2.kryo;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.h2.mvstore.ObjectArrayDataType;
import org.h2.mvstore.WriteBuffer;
import org.h2.mvstore.type.BasicDataType;
import org.objenesis.strategy.StdInstantiatorStrategy;
import org.sensorhub.impl.datastore.h2.H2Utils;
import org.sensorhub.impl.serialization.kryo.VersionedSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.esotericsoftware.kryo.ClassResolver;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.SerializerFactory;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.util.DefaultInstantiatorStrategy;
import com.esotericsoftware.kryo.util.Pool;
import com.google.common.collect.ImmutableMap;


public class KryoDataType extends BasicDataType<Object> implements ObjectArrayDataType
{
    static Logger log = LoggerFactory.getLogger(KryoDataType.class);
    
    final Pool<KryoInstance> kryoReadPool;
    final Map<Long, KryoInstance> kryoWritePool;
    protected Supplier<ClassResolver> classResolver;
    protected Consumer<Kryo> configurator;
    protected SerializerFactory<?> defaultObjectSerializer;
    protected int maxWriteBufferSize;
    
    
    static class KryoInstance
    {
        Kryo kryo;
        Output output;
        Input input;
        volatile int avgRecordSize;
        
        KryoInstance(SerializerFactory<?> defaultObjectSerializer, ClassResolver classResolver, Consumer<Kryo> configurator)
        {
            kryo = classResolver != null ? new Kryo(classResolver, null) : new Kryo();
            kryo.setRegistrationRequired(false);
            kryo.setReferences(true);
            
            // instantiate classes using default (private) constructor when available
            // or using direct JVM technique when needed
            kryo.setInstantiatorStrategy(new DefaultInstantiatorStrategy(new StdInstantiatorStrategy()));
            
            // set default object serializer
            kryo.setDefaultSerializer(defaultObjectSerializer);
                
            // configure kryo instance
            if (configurator != null)
                configurator.accept(kryo);
            
            // load persistent class mappings if needed
            if (classResolver instanceof PersistentClassResolver)
                ((PersistentClassResolver) classResolver).loadMappings();
            
            input = new Input();
            
            log.debug("{}: kryo={}, classResolver={}", 
                Thread.currentThread(),
                System.identityHashCode(kryo),
                System.identityHashCode(classResolver));
        }
    }
    
    
    public KryoDataType()
    {
        this(10*1024*1024);
    }
    
    
    public KryoDataType(final int maxWriteBufferSize)
    {
        //Log.set(Log.LEVEL_TRACE);
        this.maxWriteBufferSize = maxWriteBufferSize;
        
        // set default serializer to our versioned serializer
        this.defaultObjectSerializer = VersionedSerializer.<Object>factory2(ImmutableMap.of(
            H2Utils.CURRENT_VERSION, new SerializerFactory.FieldSerializerFactory()),
            H2Utils.CURRENT_VERSION);
        
        // use a pool of kryo objects for reading
        this.kryoReadPool = new Pool<KryoInstance>(true, false, 2*Runtime.getRuntime().availableProcessors()) {
            @Override
            protected KryoInstance create()
            {
                return new KryoInstance(
                    defaultObjectSerializer,
                    classResolver != null ? classResolver.get() : null,
                    configurator);
            }
        };
        
        // use a map of kryo objects for writing
        // the map provides a separate kryo object for each record type
        // so we can keep track of average size for each type of record separately
        // use a concurrent map because it's also accessed in getMemory() calls on page
        // reads that can happen concurrently with writes
        this.kryoWritePool = new ConcurrentHashMap<>();
    }
    
    
    protected KryoInstance getReadKryo()
    {
        return kryoReadPool.obtain();
    }
    
    
    protected KryoInstance getWriteKryo(Object obj)
    {
        var key = getRecordTypeKey(obj);
        var kryoI = kryoWritePool.computeIfAbsent(key, k -> {
            var kryo = new KryoInstance(
                defaultObjectSerializer,
                classResolver != null ? classResolver.get() : null,
                configurator);
            
            // compute initial record size and buffer size
            kryo.output = new Output(8*1024, maxWriteBufferSize);
            initRecordSize(kryo, obj);
            
            return kryo;
        });
        
        /*System.err.println(obj.getClass().getCanonicalName() + "(" + key + "): " +
            "avgRecordSize=" + kryoI.avgRecordSize + ", " +
            "writeBufferSize=" + kryoI.output.getBuffer().length);*/
        return kryoI;
    }
    
    
    protected void releaseReadKryo(KryoInstance kryoI)
    {
        kryoReadPool.free(kryoI);
    }


    @Override
    public int getMemory(Object obj)
    {
        return computeRecordSize(obj);
    }


    @Override
    public Object read(ByteBuffer buff)
    {
        KryoInstance kryoI = getReadKryo();
        var obj = read(buff, kryoI);
        releaseReadKryo(kryoI);
        return obj;
    }


    @Override
    public void read(ByteBuffer buff, Object storage, int len)
    {
        Object[] obj = cast(storage);
        KryoInstance kryoI = getReadKryo();
        for (int i = 0; i < len; i++)
            obj[i] = read(buff, kryoI);
        releaseReadKryo(kryoI);
    }


    protected Object read(ByteBuffer buff, KryoInstance kryoI)
    {
        Kryo kryo = kryoI.kryo;
        Input input = kryoI.input;
        
        // H2 2.x passes slices of an unsaved chunk's buffer, so the page may not start at array index 0
        int offset = buff.arrayOffset();
        input.setBuffer(buff.array(), offset + buff.position(), buff.remaining());
        Object obj = kryo.readClassAndObject(input);
        buff.position(input.position() - offset);
        
        return obj;
    }


    @Override
    public void write(WriteBuffer buff, Object obj)
    {
        KryoInstance kryoI = getWriteKryo(obj);
        write(buff, obj, kryoI);
    }


    @Override
    public void write(WriteBuffer buff, Object storage, int len)
    {
        Object[] objects = cast(storage);
        for (int i = 0; i < len; i++)
        {
            var obj = objects[i];
            KryoInstance kryoI = getWriteKryo(obj);
            write(buff, obj, kryoI);
        }
    }
    
    
    protected void write(WriteBuffer buff, Object obj, KryoInstance kryoI)
    {
        Kryo kryo = kryoI.kryo;
        Output output = kryoI.output;
        output.setPosition(0);
        
        //kryo.writeObjectOrNull(output, obj, objectType);
        kryo.writeClassAndObject(output, obj);
        buff.put(output.getBuffer(), 0, output.position());
        
        // adjust the average size
        int size = output.position();
        updateRecordSize(kryoI, obj, size);
    }
    
    
    /**
     * Gets a key that is unique per record type
     * This must be overridden if several types of records of different
     * sizes are multiplexed in the same datastore (e.g. observations)
     * @param obj
     * @return
     */
    protected long getRecordTypeKey(Object obj)
    {
        return 0;
    }
    
    
    /* Methods used to automatically compute average record size */
    /* This is used to inform H2 of the approximate memory used by records */
    
    protected int computeRecordSize(Object obj)
    {
        KryoInstance kryoI = getWriteKryo(obj);
        return (int)kryoI.avgRecordSize;
    }
    
    
    protected void updateRecordSize(KryoInstance kryoI, Object obj, int size)
    {
        // adjust average record size using an exponential moving average
        kryoI.avgRecordSize = (size + 15*kryoI.avgRecordSize) / 16;
    }
    
    
    protected void initRecordSize(KryoInstance kryoI, Object obj)
    {
        Kryo kryo = kryoI.kryo;
        Output output = kryoI.output;
        output.setPosition(0);
        kryo.writeClassAndObject(output, obj);
        kryoI.avgRecordSize = output.position();
    }
    
    
    @Override
    public int compare(Object a, Object b)
    {
        // can be overriden by subclass if object is used as key
        return 0;
    }

}
