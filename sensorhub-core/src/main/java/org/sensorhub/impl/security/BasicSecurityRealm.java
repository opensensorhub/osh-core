/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2016 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.security;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.module.IModuleStateManager;
import org.sensorhub.api.security.IAuthorizer;
import org.sensorhub.api.security.IPermissionPath;
import org.sensorhub.api.security.IRoleRegistry;
import org.sensorhub.api.security.IUserInfo;
import org.sensorhub.api.security.IUserPermissions;
import org.sensorhub.api.security.IUserRegistry;
import org.sensorhub.api.security.IUserRole;
import org.sensorhub.impl.module.AbstractModule;
import org.sensorhub.impl.security.BasicSecurityRealmConfig.PermissionsConfig;
import org.sensorhub.impl.security.BasicSecurityRealmConfig.RoleConfig;
import org.sensorhub.impl.security.BasicSecurityRealmConfig.UserConfig;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;


@SuppressWarnings("serial")
public class BasicSecurityRealm extends AbstractModule<BasicSecurityRealmConfig>
{
    static final String USER_PERMISSIONS_FILENAME = "user_permissions.json";
    static final String ROLE_PERMISSIONS_FILENAME = "role_permissions.json";
    
    UserStore users = new UserStore();
    RoleStore roles = new RoleStore();
    IAuthorizer authz = new DefaultAuthorizerImpl(roles);
    
    
    class UserPermissionsStore<T extends IUserPermissions> extends ConcurrentSkipListMap<String, T>
    {
        public void addAllowPermissions(String id, Collection<IPermissionPath> permissions)
        {
            var config = getPermissionsConfig(id);
            if (config == null)
                return;
            config.allowList.addAll(permissions);
            saveState();
        }

        public void addDenyPermissions(String id, Collection<IPermissionPath> permissions)
        {
            var config = getPermissionsConfig(id);
            if (config == null)
                return;
            config.denyList.addAll(permissions);
            saveState();
        }

        public void removeAllowPermissions(String id, Collection<IPermissionPath> permissions)
        {
            var config = getPermissionsConfig(id);
            if (config == null)
                return;
            config.allowList.removeAll(permissions);
            saveState();
        }

        public void removeDenyPermissions(String id, Collection<IPermissionPath> permissions)
        {
            var config = getPermissionsConfig(id);
            if (config == null)
                return;
            config.denyList.removeAll(permissions);
            saveState();
        }
        
        /*
         * The registry is a map, so other modules can add entries of their own
         * class. This realm only owns the permissions of its config entries.
         */
        protected PermissionsConfig getPermissionsConfig(String id)
        {
            var entity = get(id);
            if (entity instanceof PermissionsConfig)
                return (PermissionsConfig)entity;
            
            if (entity == null)
                getLogger().debug("Permission update skipped for '{}': no such entry", id);
            else
                getLogger().debug("Permission update skipped for '{}': the entry is managed by another module", id);
            return null;
        }
        
        protected void saveState()
        {
            try
            {
                var stateManager = getParentHub().getModuleRegistry().getStateManager(getLocalID());
                if (stateManager == null)
                    return;
                var fileName = this == users ? USER_PERMISSIONS_FILENAME : ROLE_PERMISSIONS_FILENAME;
                savePermissionJsonFile(this, stateManager, fileName);
            }
            catch (SensorHubException | RuntimeException e)
            {
                getLogger().error(e.getMessage(), e);
            }
        }
    }
    
    class UserStore extends UserPermissionsStore<IUserInfo> implements IUserRegistry {}
    class RoleStore extends UserPermissionsStore<IUserRole> implements IRoleRegistry {}


    @Override
    public void setConfiguration(BasicSecurityRealmConfig config)
    {
        super.setConfiguration(config);
        
        // add default admin user and role if none are set
        if (config.users.isEmpty() && config.roles.isEmpty())
        {
            RoleConfig role = new RoleConfig();
            role.roleID = "admin";
            role.allow.add("*");
            config.roles.add(role);
            
            UserConfig user = new UserConfig();
            user.userID = "admin";
            user.password = "test";
            user.roles.add("admin");
            config.users.add(user);
        }
    }
    
    
    @Override
    protected void doInit() throws SensorHubException
    {
        // build user map
        users.clear();
        for (UserConfig user: config.users)
        {
            user.refreshPermissionLists(getParentHub().getModuleRegistry());
            users.put(user.userID, user);
        }
        
        // build role map
        roles.clear();
        for (RoleConfig role: config.roles)
        {
            role.refreshPermissionLists(getParentHub().getModuleRegistry());
            roles.put(role.roleID, role);
        }
    }


    @Override
    protected void doStart() throws SensorHubException
    {
        getParentHub().getSecurityManager().registerUserRegistry(users);
        getParentHub().getSecurityManager().registerAuthorizer(authz);
    }
    

    @Override
    protected void doStop() throws SensorHubException
    {
        getParentHub().getSecurityManager().unregisterUserRegistry(users);
        getParentHub().getSecurityManager().unregisterAuthorizer(authz);
    }
    
    
    @Override
    public void saveState(IModuleStateManager saver) throws SensorHubException
    {
        savePermissionJsonFile(users, saver, USER_PERMISSIONS_FILENAME);
        savePermissionJsonFile(roles, saver, ROLE_PERMISSIONS_FILENAME);
    }
    
    
    protected void savePermissionJsonFile(Map<String, ? extends IUserPermissions> permissions, IModuleStateManager saver, String fileName) throws SensorHubException
    {
        // build the whole document first so the file is never left truncated
        var json = new StringWriter();
        try (var w = new JsonWriter(json))
        {
            w.setIndent("  ");
            w.beginObject();
            for (var e: permissions.entrySet())
            {
                // skip entries added by other modules
                if (!(e.getValue() instanceof PermissionsConfig))
                    continue;
                
                var id = e.getKey();
                var entity = (PermissionsConfig)e.getValue();
                
                w.name(id).beginObject();
                
                w.name("allow").beginArray();
                for (var p: entity.getAllowList())
                {
                    var permStr = PermissionFactory.getPermissionString(p);
                    if (!entity.allow.contains(permStr))
                        w.value(permStr);
                }
                w.endArray();
                
                w.name("deny").beginArray();
                for (var p: entity.getDenyList())
                {
                    var permStr = PermissionFactory.getPermissionString(p);
                    if (!entity.deny.contains(permStr))
                        w.value(permStr);
                }
                w.endArray();
                
                w.endObject();
            }
            w.endObject();
        }
        catch (IOException | RuntimeException e)
        {
            throw new SensorHubException("Error writing " + fileName, e);
        }
        
        try (var w = new OutputStreamWriter(saver.getOutputStream(fileName)))
        {
            w.write(json.toString());
        }
        catch (IOException e)
        {
            throw new SensorHubException("Error writing " + fileName, e);
        }
    }


    @Override
    public void loadState(IModuleStateManager loader) throws SensorHubException
    {
        readPermissionJsonFile(users, loader, USER_PERMISSIONS_FILENAME);
        readPermissionJsonFile(roles, loader, ROLE_PERMISSIONS_FILENAME);
    }
    
    
    protected void readPermissionJsonFile(Map<String, ? extends IUserPermissions> permissions, IModuleStateManager loader, String fileName) throws SensorHubException
    {
        var moduleReg = getParentHub().getModuleRegistry();
        
        try (var is = loader.getAsInputStream(fileName))
        {
            if (is == null)
                return;
            var r = new JsonReader(new InputStreamReader(is));
            
            r.beginObject();
            
            while (r.hasNext())
            {
                var id = r.nextName();
                
                // skip entries that are unknown or were added by other modules
                if (!(permissions.get(id) instanceof PermissionsConfig))
                {
                    r.skipValue();
                    continue;
                }
                var entity = (PermissionsConfig)permissions.get(id);
                
                r.beginObject();
                while (r.hasNext())
                {
                    var propName = r.nextName();
                    
                    if ("allow".equals(propName))
                    {
                        r.beginArray();
                        while (r.hasNext())
                        {
                            var perm = PermissionFactory.newPermissionSetting(moduleReg, r.nextString());
                            entity.allowList.add(perm);
                        }
                        r.endArray();
                    }
                    
                    else if ("deny".equals(propName))
                    {
                        r.beginArray();
                        while (r.hasNext())
                        {
                            var perm = PermissionFactory.newPermissionSetting(moduleReg, r.nextString());
                            entity.denyList.add(perm);
                        }
                        r.endArray();
                    }
                    
                    else
                        r.skipValue();
                }
                r.endObject();
            }
            r.endObject();
        }
        catch (IOException | RuntimeException e)
        {
            throw new SensorHubException("Error reading " + fileName, e);
        }
    }


    @Override
    public void cleanup() throws SensorHubException
    {
    }
    
}
