/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2021 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.datastore.h2.index;

import org.h2.mvstore.MVMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.esotericsoftware.kryo.ClassResolver;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Registration;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.util.CuckooObjectMap;
import com.esotericsoftware.kryo.util.IntMap;
import com.esotericsoftware.kryo.util.Util;


/**
 * <p>
 * Extension of Kryo class resolver to persist class <-> id mappings in the
 * database itself
 * </p>
 *
 * @author Alex Robin
 * @since Sep 20, 2021
 */
@SuppressWarnings("rawtypes")
public class PersistentClassResolver implements ClassResolver
{
    static Logger log = LoggerFactory.getLogger(PersistentClassResolver.class);
    
    final IntMap<Registration> idToRegistration = new IntMap<>();
    final CuckooObjectMap<Class, Registration> classToRegistration = new CuckooObjectMap<>();
    final MVMap<String, Integer> classNameToIdMap;
    Kryo kryo;
    boolean mappingsLoaded; 
    
    
    public PersistentClassResolver(MVMap<String, Integer> classNameToIdMap)
    {
        this.classNameToIdMap = classNameToIdMap;
    }
    
    
    public synchronized void loadMappings()
    {
        if (mappingsLoaded)
            return;
        
        // preload mappings on startup
        int lastId = 0;
        var it = classNameToIdMap.entrySet().iterator();
        while (it.hasNext())
        {
            var entry = it.next();
            
            try
            {
                var className = entry.getKey();
                var classId = entry.getValue();
                register(Class.forName(className), classId);
                log.debug("Loading class mapping: {} -> {}", className, classId);
                
                if (classId > lastId)
                    lastId = classId;
            }
            catch (ClassNotFoundException e)
            {
                throw new IllegalStateException("Error loading class mapping", e);
            }
        }
        
        mappingsLoaded = true;
    }
    
    
    public Registration writeClass(Output output, Class type)
    {
        if (type == null)
        {
            output.writeByte(Kryo.NULL);
            return null;
        }
        
        var reg = kryo.getRegistration(type);
        
        // if first time class is written
        // register mapping and add to persistent map
        int classId;
        if (reg == null || reg.getId() < 0)
        {
            // synchronize since multiple Kryo instance can share this class resolver
            synchronized (classNameToIdMap.getStore())
            {
                var clazz = type;
                classId = classNameToIdMap.computeIfAbsent(type.getName(), name -> {
                    // keep 10 slots for primitive types registered by Kryo
                    var nextId = classNameToIdMap.size()+10;
                    log.debug("Adding class mapping: {} -> {}", clazz, nextId);
                    return nextId;
                });
            }
            
            reg = register(type, classId);
        }
        else
            classId = reg.getId();
        
        // always write integer class ID
        output.writeVarInt(classId+1, true);
        
        return reg;
    }
    
    
    public Registration readClass(Input input)
    {
        // read integer class ID
        int classId = input.readVarInt(true);
        
        if (classId == Kryo.NULL)
            return null;
        
        return idToRegistration.get(classId-1);
    }
    
    
    protected Registration register(Class type, int classId)
    {
        return register(new Registration(type, kryo.getDefaultSerializer(type), classId));
    }


    @Override
    public Registration register(Registration registration)
    {
        idToRegistration.put(registration.getId(), registration);
        
        classToRegistration.put(registration.getType(), registration);
        Class wrapperClass = Util.getWrapperClass(registration.getType());
        if (wrapperClass != registration.getType()) classToRegistration.put(wrapperClass, registration);
        
        return registration;
    }


    @Override
    public Registration unregister(int classID)
    {
        return null;
    }


    @Override
    public Registration registerImplicit(Class type)
    {
        return register(type, -1);
    }


    @Override
    public Registration getRegistration(Class type)
    {
        return classToRegistration.get(type);
    }


    @Override
    public Registration getRegistration(int classID)
    {
        return idToRegistration.get(classID);
    }


    @Override
    public void setKryo(Kryo kryo)
    {
        this.kryo = kryo;
    }


    @Override
    public synchronized void reset()
    {
    }
}