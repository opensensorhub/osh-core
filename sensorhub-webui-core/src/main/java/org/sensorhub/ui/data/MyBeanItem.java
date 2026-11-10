/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.ui.data;

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.sensorhub.api.config.DisplayInfo.IdField;
import com.vaadin.v7.data.Item;
import com.vaadin.v7.data.Property;
import com.vaadin.v7.data.util.MethodProperty;


/**
 * <p>
 * Custom bean item to also generate properties for public fields
 * (i.e. even without getter and setter methods)
 * </p>
 *
 * @author Alex Robin
 * @param <BeanType> Type of java bean wrapped by this class
 * @since Nov 24, 2013
 */
@SuppressWarnings("serial")
public class MyBeanItem<BeanType> implements Item
{
    public static final String NO_PREFIX = "";
    public static final char PROP_SEPARATOR = '.';
    
    
    transient BeanType bean;
    HashMap<Object, Property<?>> properties = new LinkedHashMap<>();
    String prefix = NO_PREFIX;
    
    
    public MyBeanItem(BeanType bean)
    {
        this.bean = bean;
        addProperties(NO_PREFIX, bean);
    }
    
    
    public MyBeanItem(BeanType bean, String prefix)
    {
        this.bean = bean;
        this.prefix = prefix;
        addProperties(prefix, bean);
    }
    
    
    protected void addProperties(String prefix, Object bean)
    {
        // special case for String
        if (!BeanUtils.isSimpleType(bean.getClass()))
        {
            addFieldProperties(prefix, bean);
            addMethodProperties(prefix, bean);
        }
    }
    
    
    protected void addFieldProperties(String prefix, Object bean)
    {
        for (Field f: getFields(bean.getClass(), Modifier.PUBLIC))
        {
            // skip static and transient fields
            int modifiers = f.getModifiers();
            if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers))
                continue;
            
            String fullName = prefix + f.getName();
            Class<?> fieldType = f.getType();
            Object fieldVal = null;
            try
            {
                fieldVal = f.get(bean);
            }
            catch (Exception e)
            {
                throw new IllegalStateException("Cannot access property " + fullName, e);
            }
            
            // case of simple types or enum instances
            if (BeanUtils.isSimpleType(f) || fieldVal instanceof Enum<?>)
            {
                //System.out.println("field " + fullName);
                addItemProperty(fullName, new FieldProperty(bean, f));
            }
            
            // case of collections
            else if (Collection.class.isAssignableFrom(fieldType))
            {
                ParameterizedType listType = (ParameterizedType)f.getGenericType();
                Class<?> eltType = (Class<?>)listType.getActualTypeArguments()[0];
                
                Collection<?> listObj = (Collection<?>)fieldVal;
                MyBeanItemContainer<Object> container = new MyBeanItemContainer<>(listObj, eltType, fullName + PROP_SEPARATOR);
                addItemProperty(fullName, new ContainerProperty(bean, f, container));
            }
            
            // case of arrays
            else if (fieldType.isArray())
            {
                
            }
            
            // case of maps
            else if (Map.class.isAssignableFrom(fieldType))
            {
                ParameterizedType mapType = (ParameterizedType)f.getGenericType();
                Class<?> eltType = (Class<?>)mapType.getActualTypeArguments()[1];
                
                Map<String, ?> mapObj = (Map<String, ?>)fieldVal;
                MyBeanItemContainer<Object> container = new MyBeanItemContainer<>(mapObj, eltType, fullName + PROP_SEPARATOR);
                addItemProperty(fullName, new MapProperty(bean, f, container));
            }
            
            // case of nested objects
            else
            {
                MyBeanItem<Object> beanItem = null;
                if (fieldVal != null)
                    beanItem = new MyBeanItem<>(fieldVal, fullName + PROP_SEPARATOR);
                addItemProperty(fullName, new ComplexProperty(bean, f, beanItem));
            }
        }
    }
    
    
    @SuppressWarnings("rawtypes")
    protected void addMethodProperties(String prefix, Object bean)
    {
        Map<String, Field> fieldMap = new HashMap<>();
        for (Field f: getFields(bean.getClass(), Modifier.PROTECTED | Modifier.PRIVATE))
            fieldMap.put(f.getName(), f);
        
        for (PropertyDescriptor prop: getGettersAndSetters(bean.getClass()))
        {
            if (!fieldMap.containsKey(prop.getName()))
                continue;
            
            String fullName = prefix + prop.getName();
            
            // Getter-backed collections need the same list editor as public fields.
            // In particular, live collections often have no setter.
            Field field = fieldMap.get(prop.getName());
            if (Collection.class.isAssignableFrom(prop.getPropertyType()) && prop.getReadMethod() != null
                && field.getGenericType() instanceof ParameterizedType)
            {
                var listType = (ParameterizedType)field.getGenericType();
                if (listType.getActualTypeArguments()[0] instanceof Class<?>)
                {
                    try
                    {
                        field.setAccessible(true);
                        var list = (Collection<?>)prop.getReadMethod().invoke(bean);
                        var elementType = (Class<?>)listType.getActualTypeArguments()[0];
                        var container = new MyBeanItemContainer<Object>(list, elementType, fullName + PROP_SEPARATOR);
                        addItemProperty(fullName, new ContainerProperty(bean, field, container));
                        continue;
                    }
                    catch (ReflectiveOperationException e)
                    {
                        throw new IllegalStateException("Cannot access property " + fullName, e);
                    }
                }
            }

            //System.out.println(prop.getName() + ", get=" + prop.getReadMethod() + ", set=" + prop.getWriteMethod() + ", hidden=" + prop.isHidden());
            addItemProperty(fullName, new MethodProperty(prop.getPropertyType(), bean, prop.getReadMethod(), prop.getWriteMethod()));
        }
    }
    
    
    public BeanType getBean()
    {
        return bean;
    }
    
    
    public void setBean(BeanType bean)
    {
        this.bean = bean;
    }
    
    
    protected List<Field> getFields(Class<?> beanClass, int modifier)
    {
        List<Field> selectedFields = new ArrayList<>(50);
        collectFields(selectedFields, beanClass, modifier);
        return selectedFields;
    }
    
    
    protected void collectFields(List<Field> selectedFields, Class<?> clazz, int modifier)
    {
        Class<?> superClass = clazz.getSuperclass();
        if (superClass != null)
            collectFields(selectedFields, superClass, modifier);
        
        for (Field f: clazz.getDeclaredFields())
        {
            if ((f.getModifiers() & modifier) != 0)
                selectedFields.add(f);
        }
    }
    
    
    protected PropertyDescriptor[] getGettersAndSetters(Class<?> beanClass)
    {
        try
        {
            return Introspector.getBeanInfo(beanClass).getPropertyDescriptors();
        }
        catch (IntrospectionException e)
        {
            throw new IllegalStateException("Cannot introspect bean object", e);
        }
    }


    @Override
    public Property<?> getItemProperty(Object id)
    {
        return properties.get(id);
    }


    @Override
    public Collection<?> getItemPropertyIds()
    {
        return properties.keySet();
    }


    @Override
    @SuppressWarnings("rawtypes")
    public boolean addItemProperty(Object id, Property property) throws UnsupportedOperationException
    {
        properties.put(id, property);
        return true;
    }


    @Override
    public boolean removeItemProperty(Object id) throws UnsupportedOperationException
    {
        return (properties.remove(id) != null);
    }
    
    
    public String getItemId()
    {
        IdField ann = bean.getClass().getAnnotation(IdField.class);
        if (ann != null && ann.value().length() > 0)
        {
            Property<?> prop = getItemProperty(prefix + ann.value());
            if (prop != null && prop.getValue() != null)
                return prop.getValue().toString();
        }
        
        return null;
    }

    
    @Override
    public String toString()
    {
        return bean.toString();
    }
}
