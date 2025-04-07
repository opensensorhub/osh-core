/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2025 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.vast.data;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.AbstractCollection;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import org.vast.cdm.common.CDMException;
import org.vast.cdm.common.DataStreamWriter;
import org.vast.swe.SWEHelper;
import org.vast.swe.ScalarIndexer;
import org.vast.util.Asserts;
import com.google.common.collect.ImmutableMap;
import net.opengis.swe.v20.DataArray;
import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;


/**
 * <p>
 * This class dynamically generates an accessor with Java Dynamic Proxy Classes
 * using a data component schema and an interface with annotations that maps
 * get/set methods to component paths.
 * </p><p>
 * The {@link SweComponent} annotation must be used. When omitted, the
 * generator will try to infer the name of the component from the get/set method
 * name and look for a corresponding component at the root of the record. 
 * </p><p>
 * The following data types are supported in get/set methods:
 * - All primitive types
 * - java.time.Instant
 * - java.lang.Date
 * - JTS geometries
 * - SWE vector, quaternion, matrix, etc.
 * - primitive arrays?
 * </p>
 * 
 * TODO more efficient implementation based on generated methods compiled in
 * memory with Janino or equivalent.
 *
 * @author Alex Robin
 * @since Jan 20, 2025
 */
public class DataBlockProxy implements IDataAccessor, InvocationHandler
{   
    final DataComponent recordSchema;
    final Method wrapMethod;
    final Method getDblkMethod;
    final Method toStringMethod;
    final Map<Method, ScalarIndexer> indexerMap;
    DataBlockList arrayData;
    
    
    private DataBlockProxy(DataComponent recordSchema, Class<?> targetInterface)
    {
        this.recordSchema = recordSchema.copy();
         
        try
        {
            this.wrapMethod = IDataAccessor.class.getMethod("wrap", DataBlock.class);
            this.getDblkMethod = IDataAccessor.class.getMethod("getDataBlock");
            this.toStringMethod = Object.class.getMethod("toString");
        }
        catch (NoSuchMethodException e)
        {
            throw new IllegalStateException(e);
        }
        
        // list get/set methods
        
         
        // build indexer map
        indexerMap = ImmutableMap.<Method, ScalarIndexer>builderWithExpectedSize(recordSchema.getComponentCount())
            .build();
    }
    
    
    @SuppressWarnings("unchecked")
    public static <T extends IDataAccessor> T generate(DataComponent recordSchema, Class<T> targetInterface)
    {
        return (T) Proxy.newProxyInstance(
            targetInterface.getClassLoader(),
            new Class[] { targetInterface }, 
            new DataBlockProxy(recordSchema, targetInterface)
        );
    }
    
    
    @Override
    public Object invoke(Object proxy, Method method, Object[] args)
    {
        // handle hard wired methods
        // ok to use reference equality here
        if (method.getName() == wrapMethod.getName())
        {
            wrap((DataBlock)args[0]);
            return null;
        }
        else if (method.getName() == getDblkMethod.getName())
        {
            return getDataBlock();
        }
        else if (method.getName() == toStringMethod.getName())
        {
            return toString();
        }
        else if (method.isDefault())
        {
            try { return InvocationHandler.invokeDefault(proxy, method, args); }
            catch (Throwable e) { throw new RuntimeException(e); }
        }
        
        if (recordSchema.getData() == null)
            throw new IllegalStateException("No datablock has been assigned");
        
        // get component from mapping or try to guess
        var comp = findComponentData(method);
        
        if (isGetMethod(method))
        {
            var data = comp.getData();
            var retType = method.getReturnType();
                        
            if (retType == boolean.class)
                return data.getBooleanValue();
            else if (retType == byte.class)
                return data.getByteValue();
            else if (retType == short.class)
                return data.getShortValue();
            else if (retType == int.class)
                return data.getIntValue();
            else if (retType == long.class)
                return data.getLongValue();
            else if (retType == float.class)
                return data.getFloatValue();
            else if (retType == double.class)
                return data.getDoubleValue();
            else if (retType == String.class)
                return data.getStringValue();
            else if (retType == Instant.class)
                return data.getTimeStamp();
            else if (retType == OffsetDateTime.class)
                return data.getDateTime();
            else if (Collection.class.isAssignableFrom(retType))
            {
                assertDataArray(method, comp);
                
                var itemType = (Class<?>)((ParameterizedType)method.getGenericReturnType()).getActualTypeArguments()[0];
                if (!IDataAccessor.class.isAssignableFrom(itemType))
                    throw new IllegalStateException("Collection item type must be an accessor class");
                
                @SuppressWarnings({"unchecked"})
                var accessorClass = (Class<IDataAccessor>)itemType;
                return createCollection(accessorClass, (DataArray)comp);
            }
            else
                throw new IllegalStateException("Unsupported datatype: " + retType);
        }
        
        else if (isSetNumMethod(method))
        {
            assertDataArray(method, comp);
            var arraySize = (int)args[0];
            ((DataArray)comp).updateSize(arraySize);
            return null;
        }
        
        else if (isSetMethod(method))
        {
            var data = comp.getData();
            var argType = method.getParameters()[0].getType();
            var val = args[0];
                        
            if (argType == boolean.class)
                data.setBooleanValue((boolean)val);
            else if (argType == byte.class)
                data.setByteValue((byte)val);
            else if (argType == short.class)
                data.setShortValue((short)val);
            else if (argType == int.class)
                data.setIntValue((int)val);
            else if (argType == long.class)
                data.setLongValue((long)val);
            else if (argType == float.class)
                data.setFloatValue((float)val);
            else if (argType == double.class)
                data.setDoubleValue((double)val);
            else if (argType == String.class)
                data.setStringValue((String)val);
            else if (argType == Instant.class)
                data.setTimeStamp((Instant)val);
            else if (argType == OffsetDateTime.class)
                data.setDateTime((OffsetDateTime)val);
            else
                throw new IllegalStateException("Unsupported datatype: " + argType);
            return null;
        }
        
        else if (isAddMethod(method))
        {
            var retType = method.getReturnType();
            Asserts.checkState(IDataAccessor.class.isAssignableFrom(retType),
                "Return type of method " + method.getName() + " must be a IDataAccessor");
            
            var array = assertDataArray(method, comp);
            if (arrayData == null)
            {
                arrayData = new DataBlockList(true);
                array.setData(arrayData);
                array.updateSize(0);
                var parent = ((AbstractRecordImpl<?>)array.getParent());
                parent.updateDataBlock();
            }
            
            // create new element data block
            var newDblk = array.getElementType().createDataBlock();
            arrayData.add(newDblk);
            ((DataArrayImpl)array).updateSizeComponent(array.getComponentCount()+1);
            var parent = ((AbstractDataComponentImpl)array.getParent());
            parent.updateAtomCount(newDblk.getAtomCount());
            
            // create accessor for new element
            @SuppressWarnings("unchecked")
            var accessorClass = (Class<IDataAccessor>)retType;
            var accessor = createElementProxy(accessorClass, array.getElementType());
            accessor.wrap(newDblk);
            
            return accessor;
        }
        
        else
            return null;
    }
    
    
    protected DataComponent findComponentData(Method m)
    {
        String path = null;
        
        var annotations = m.getAnnotationsByType(SweMapping.class);
        if (annotations.length > 0)
        {
            var sweMapping = annotations[0];
            path = sweMapping.path();
        }
        
        try
        {
            return SWEHelper.findComponentByPath(recordSchema, path);
        }
        catch (CDMException e)
        {
            throw new IllegalStateException("Invalid component path: " + path, e);
        }
    }
    
    
    protected boolean isGetMethod(Method m)
    {
        return m.getName().startsWith("get") &&
               m.getReturnType() != void.class &&
               m.getParameters().length == 0;
    }
    
    
    protected boolean isSetMethod(Method m)
    {
        return m.getName().startsWith("set") &&
               m.getReturnType() == void.class &&
               m.getParameters().length == 1;
    }
    
    
    protected boolean isAddMethod(Method m)
    {
        return m.getName().startsWith("add") &&
               m.getReturnType() != void.class &&
               m.getParameters().length == 0;
    }
    
    
    protected boolean isSetNumMethod(Method m)
    {
        return m.getName().startsWith("setNum") &&
               m.getReturnType() == void.class &&
               m.getParameters().length == 1;
    }
    
    
    protected IDataAccessor createElementProxy(Class<IDataAccessor> clazz, DataComponent arrayElt)
    {
        try
        {
            return DataBlockProxy.generate(arrayElt, clazz);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("Error creating array element proxy class", e);
        }
    }
    
    
    protected Collection<IDataAccessor> createCollection(Class<IDataAccessor> clazz, DataArray array)
    {
        return new AbstractCollection<IDataAccessor>() {

            @Override
            public Iterator<IDataAccessor> iterator()
            {
                return new Iterator<IDataAccessor>()
                {
                    int idx;
                    
                    @Override
                    public boolean hasNext()
                    {
                        return idx < array.getComponentCount();
                    }

                    @Override
                    public IDataAccessor next()
                    {
                        var elt = array.getComponent(idx++);
                        var accessor = createElementProxy(clazz, array.getElementType());
                        accessor.wrap(elt.getData());
                        return accessor;
                    }
                };
            }

            @Override
            public int size()
            {
                return array.getComponentCount();
            }
            
            @Override
            public void clear()
            {
                array.clearData();
            }
        };
    }
    
    
    protected DataArray assertDataArray(Method m, DataComponent comp)
    {
        Asserts.checkState(comp instanceof DataArray, "Component referenced by " + m.getName() + " method must be a DataArray");
        return (DataArray)comp;
    }


    @Override
    public void wrap(DataBlock db)
    {
        recordSchema.setData(db);
        arrayData = null;
    }


    @Override
    public DataBlock getDataBlock()
    {
        return recordSchema.getData();
    }
    
    
    @Override
    public String toString()
    {
        try
        {
            var baos = new ByteArrayOutputStream();
            DataStreamWriter sweWriter = SWEHelper.createDataWriter(new JSONEncodingImpl());
            sweWriter.setDataComponents(recordSchema);
            sweWriter.setOutput(baos);
            sweWriter.write(recordSchema.getData());
            sweWriter.flush();
            return baos.toString(StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Error writing to String", e);
        }
    }
}
