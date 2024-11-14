/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.module;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.sensorhub.api.module.IModuleConfigRepository;
import org.sensorhub.api.module.ModuleConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.internal.Streams;
import com.google.gson.internal.bind.JsonTreeReader;
import com.google.gson.internal.bind.JsonTreeWriter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.Reader;
import org.sensorhub.utils.ModuleUtils;

/**
 * <p>
 * Class providing access to the configuration database that is used to
 * persist all modules' configuration.
 * </p>
 *
 * @author Alex Robin <alex.robin@sensiasoftware.com>
 * @since Sep 3, 2013
 */
public class ModuleConfigJsonFile implements IModuleConfigRepository
{
    private static final Logger log = LoggerFactory.getLogger(ModuleConfigJsonFile.class);
    private static final String OBJ_CLASS_FIELD = "objClass";
    
    Map<String, ModuleConfig> configMap;
    Gson gson;
    File configFile;
    boolean keepBackup;
    
    
    /* GSON type adapter factory for parsing JSON object to a custom subclass.
     * The desired class is indicated by an additional field, whose name is
     * configured by typeFieldName. */
    public final class RuntimeTypeAdapterFactory<T> implements TypeAdapterFactory
    {
        private final Class<?> baseType;
        private final String typeFieldName;


        public RuntimeTypeAdapterFactory(Class<?> baseType, String typeFieldName)
        {
            if (typeFieldName == null || baseType == null)
                throw new NullPointerException();
            
            this.baseType = baseType;
            this.typeFieldName = typeFieldName;
        }


        @Override
        public <R> TypeAdapter<R> create(final Gson gson, final TypeToken<R> type)
        {
            if (baseType != Object.class && !type.getRawType().isInstance(baseType))
                return null;
            
            return new TypeAdapter<R>()
            {
                @Override
                public R read(JsonReader in) throws IOException
                {
                    JsonElement jsonElement = Streams.parse(in);                
                    TypeAdapter<R> delegate = gson.getDelegateAdapter(RuntimeTypeAdapterFactory.this, type);
                    
                    if (jsonElement.isJsonObject())
                    {
                        JsonElement typeField = jsonElement.getAsJsonObject().remove(typeFieldName);
                                            
                        if (typeField != null)
                        {
                            String type = typeField.getAsString();
                            
                            try
                            {
                                Class<R> runtimeClass = (Class<R>)Class.forName(type);
                                delegate = gson.getDelegateAdapter(RuntimeTypeAdapterFactory.this, TypeToken.get(runtimeClass));                        
                            }
                            catch (ClassNotFoundException e)
                            {
                                throw new IllegalStateException("Runtime class specified in JSON is invalid: " + type, e);
                            }
                        }
                    }
                    
                    JsonReader jsonReader = new JsonTreeReader(jsonElement);
                    jsonReader.setLenient(true);
                    return delegate.read(jsonReader);
                }


                @Override
                public void write(JsonWriter out, R value) throws IOException
                {
                    Class<R> runtimeClass = (Class<R>)value.getClass();
                    String typeName = runtimeClass.getName();
                    TypeAdapter<R> delegate = gson.getDelegateAdapter(RuntimeTypeAdapterFactory.this, TypeToken.get(runtimeClass));
                    
                    //JsonElement jsonElt = delegate.toJsonTree(value); // JsonTreeWriter is not lenient in this case                    
                    JsonTreeWriter jsonWriter = new JsonTreeWriter();
                    jsonWriter.setLenient(true);
                    jsonWriter.setSerializeNulls(false);
                    delegate.write(jsonWriter, value);
                    JsonElement jsonElt = jsonWriter.get();
                    
                    if (jsonElt.isJsonObject())
                    {
                        JsonObject jsonObject = jsonElt.getAsJsonObject();
                        JsonObject clone = new JsonObject();
                        
                        // insert class name as first attribute
                        clone.add(typeFieldName, new JsonPrimitive(typeName));
                        for (Map.Entry<String, JsonElement> e : jsonObject.entrySet())
                            clone.add(e.getKey(), e.getValue());
                        
                        jsonElt = clone;
                    }
                    
                    Streams.write(jsonElt, out);
                }
            }.nullSafe();
        }
    }
        
    
    public ModuleConfigJsonFile(String moduleConfigPath, boolean keepBackup)
    {
        this.configFile = new File(moduleConfigPath);
        if (!configFile.exists())
            throw new IllegalArgumentException("Cannot find config file " + configFile.getAbsolutePath());
        
        this.keepBackup= keepBackup;
        this.configMap = new LinkedHashMap<>();
        
        // init json serializer/deserializer
        final GsonBuilder builder = new GsonBuilder();
        builder.setLenient();
        builder.setPrettyPrinting();
        builder.disableHtmlEscaping();
        builder.serializeSpecialFloatingPointValues();
        builder.setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
        builder.registerTypeAdapterFactory(new RuntimeTypeAdapterFactory<Object>(Object.class, OBJ_CLASS_FIELD));
        
        gson = builder.create();
        readJSON();
    }
    
    
    @Override
    public List<ModuleConfig> getAllModulesConfigurations()
    {
        if (configMap.isEmpty())
            readJSON();        
        return new ArrayList<>(configMap.values());
    }
    
    
    @Override
    public boolean contains(String moduleID)
    {
        return configMap.containsKey(moduleID);
    }
    

    @Override
    public synchronized ModuleConfig get(String moduleID)
    {
        ModuleConfig conf = configMap.get(moduleID);
        if (conf == null)
            throw new IllegalArgumentException("No configuration found for module id " + moduleID);
        return conf;
    }


    @Override
    public synchronized void add(ModuleConfig... configList)
    {
        for (ModuleConfig config: configList)
        {        
            ModuleConfig conf = configMap.get(config.id);
            if (conf != null)
                throw new IllegalArgumentException("Module " + config.name + " already exists");
            
            configMap.put(config.id, config);
        }
    }
    
    
    @Override
    public synchronized void update(ModuleConfig... configList)
    {
        for (ModuleConfig config: configList)
        {
            // generate a new ID if non was provided
            if (config.id == null)
                config.id = UUID.randomUUID().toString();
            
            configMap.put(config.id, config); 
        }
    }
    
    
    @Override
    public synchronized void remove(String... moduleIDs)
    {
        for (String moduleID: moduleIDs)
        {
            get(moduleID); // check if module exists
            configMap.remove(moduleID);
        }
    }
    
    
    @Override
    public synchronized void commit()
    {
        // keep old config with .bak extension
        if (keepBackup && configFile.exists())
        {
            try
            {
                String timetag = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
                File bakFile = new File(configFile.getAbsolutePath() + ".bak." + timetag );
                Files.move(configFile.toPath(), bakFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            catch (IOException e)
            {
                log.error("Could not backup previous config file", e);
            }
        }
        
        writeJSON();
    }
    
    
    @Override
    public synchronized void close()
    {
        // comment this because it overrides changes made to config file everytime!
        //commit();
    }
    
    
    /*
     * Reads all modules configuration from the given JSON config file
     */
    private void readJSON()
    {
        try (FileReader reader = new FileReader(configFile))
        {
            Type collectionType = new TypeToken<List<ModuleConfig>>(){}.getType();
            JsonReader jsonReader = new JsonReaderWithVarExpansion(reader);
            List<ModuleConfig> configList = gson.fromJson(jsonReader, collectionType);
            
            // build module map
            configMap.clear();
            for (ModuleConfig config: configList)
            {
                if (configMap.containsKey(config.id))
                    throw new IllegalStateException("Duplicate module ID " + config.id);
                configMap.put(config.id, config);
            }
        }
        catch (Exception e)
        {
            throw new IllegalStateException("Error while parsing module config file " + configFile.getAbsolutePath(), e);
        }
    }
    
    
    private void writeJSON()
    {
        try (FileWriter writer = new FileWriter(configFile))
        {
            Collection<ModuleConfig> configList = configMap.values();
            
            Type collectionType = new TypeToken<List<ModuleConfig>>(){}.getType();
            writer.append(gson.toJson(configList, collectionType));
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Error while writing JSON config file " + configFile.getAbsolutePath(), e);
        }
    }

    static class JsonReaderWithVarExpansion extends JsonReader
    {
        public JsonReaderWithVarExpansion(Reader in)
        {
            super(in);
        }

        @Override
        public String nextString() throws IOException
        {
            String str = super.nextString();
            return ModuleUtils.expand(str, true);

            // TODO save original string w/ variable so we can save it back
            // if it wasn't set to a new value
        }
    }
}
