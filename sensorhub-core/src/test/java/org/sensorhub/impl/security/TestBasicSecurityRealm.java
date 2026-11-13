/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.

******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.security;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.module.IModuleStateManager;
import org.sensorhub.api.security.IPermissionPath;
import org.sensorhub.api.security.IUserInfo;
import org.sensorhub.impl.SensorHub;
import org.sensorhub.impl.SensorHubConfig;
import org.sensorhub.impl.module.DefaultModuleStateManager;
import org.sensorhub.impl.security.BasicSecurityRealmConfig.RoleConfig;
import org.sensorhub.impl.security.BasicSecurityRealmConfig.UserConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;


public class TestBasicSecurityRealm
{
    static final String RUNTIME_PERM = "*/get";
    static final String OTHER_USER = "bob";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    SensorHub hub;
    IModuleStateManager state;
    File userFile;


    @Before
    public void setup() throws Exception
    {
        // module data path is set so that permission updates are written to file
        hub = new SensorHub(new SensorHubConfig(null, tmp.getRoot().getAbsolutePath()));
        hub.start();
        state = new DefaultModuleStateManager(tmp.getRoot().getAbsolutePath(), "realm");
        userFile = new File(state.getDataDirectory(), BasicSecurityRealm.USER_PERMISSIONS_FILENAME);
    }


    /* realm with config users 'admin' and 'user1' */
    private BasicSecurityRealm newRealm() throws Exception
    {
        var config = new BasicSecurityRealmConfig();
        config.id = "realm";

        var role = new RoleConfig();
        role.roleID = "admin";
        role.allow.add("*");
        config.roles.add(role);

        for (var id: List.of("admin", "user1"))
        {
            var user = new UserConfig();
            user.userID = id;
            user.password = "pwd";
            config.users.add(user);
        }

        var realm = new BasicSecurityRealm();
        realm.setParentHub(hub);
        realm.setConfiguration(config);
        realm.doInit();
        return realm;
    }


    /* user of a class that is not owned by the realm */
    private IUserInfo otherUser(String id)
    {
        return new IUserInfo() {
            public String getId() { return id; }
            public String getName() { return id; }
            public String getDescription() { return null; }
            public String getPassword() { return "pwd"; }
            public Collection<String> getRoles() { return Collections.emptyList(); }
            public Map<String, Object> getAttributes() { return Collections.emptyMap(); }
            public Collection<IPermissionPath> getAllowList() { return Collections.emptyList(); }
            public Collection<IPermissionPath> getDenyList() { return Collections.emptyList(); }
        };
    }


    private Collection<IPermissionPath> runtimePerm()
    {
        return List.of(PermissionFactory.newPermissionSetting(hub.getModuleRegistry(), RUNTIME_PERM));
    }


    private List<String> allowStrings(BasicSecurityRealm realm, String userID)
    {
        return realm.users.get(userID).getAllowList().stream()
            .map(PermissionFactory::getPermissionString)
            .toList();
    }


    private JsonObject readUserFile() throws Exception
    {
        return JsonParser.parseString(Files.readString(userFile.toPath())).getAsJsonObject();
    }


    private void writeUserFile(String json) throws Exception
    {
        Files.writeString(userFile.toPath(), json, StandardCharsets.UTF_8);
    }


    @Test
    public void testSaveSkipsUsersOfOtherModules() throws Exception
    {
        var realm = newRealm();
        realm.users.put(OTHER_USER, otherUser(OTHER_USER));

        realm.saveState(state);

        var json = readUserFile();
        assertTrue(json.has("admin"));
        assertTrue(json.has("user1"));
        assertFalse(json.has(OTHER_USER));
    }


    @Test
    public void testSaveThenLoad() throws Exception
    {
        var realm = newRealm();
        realm.users.put(OTHER_USER, otherUser(OTHER_USER));
        ((UserConfig)realm.users.get("user1")).allowList.addAll(runtimePerm());
        realm.saveState(state);

        var realm2 = newRealm();
        assertTrue(allowStrings(realm2, "user1").isEmpty());
        realm2.loadState(state);
        assertEquals(List.of(RUNTIME_PERM), allowStrings(realm2, "user1"));
        assertTrue(allowStrings(realm2, "admin").isEmpty());
    }


    @Test
    public void testLoadSkipsUnknownAndOtherUsers() throws Exception
    {
        // 'aaa' is not in the registry, 'bob' is of another class; both come before 'user1'
        writeUserFile("{"
            + "\"aaa\": {\"allow\": [\"*/x\"], \"deny\": []},"
            + "\"" + OTHER_USER + "\": {\"allow\": [\"*/x\"], \"deny\": []},"
            + "\"user1\": {\"other\": 1, \"allow\": [\"" + RUNTIME_PERM + "\"], \"deny\": []}"
            + "}");

        var realm = newRealm();
        realm.users.put(OTHER_USER, otherUser(OTHER_USER));
        realm.loadState(state);

        assertEquals(List.of(RUNTIME_PERM), allowStrings(realm, "user1"));
    }


    @Test
    public void testLoadTruncatedFile() throws Exception
    {
        var truncated = "{\n  \"admin\": {\n    \"allow\": [],\n    \"deny\": []\n  }\n";
        writeUserFile(truncated);

        var realm = newRealm();
        assertThrows(SensorHubException.class, () -> realm.loadState(state));

        // file is kept and config users are intact
        assertEquals(truncated, Files.readString(userFile.toPath()));
        assertTrue(realm.users.containsKey("admin"));
        assertTrue(realm.users.containsKey("user1"));
    }


    @Test
    public void testLoadInvalidFile() throws Exception
    {
        writeUserFile("{\"user1\": [1, 2]}");
        var realm = newRealm();
        assertThrows(SensorHubException.class, () -> realm.loadState(state));
    }


    @Test
    public void testPermissionUpdateOnUserOfOtherModule() throws Exception
    {
        var realm = newRealm();
        realm.users.put(OTHER_USER, otherUser(OTHER_USER));

        // no exception, no change
        realm.users.addAllowPermissions(OTHER_USER, runtimePerm());
        realm.users.addDenyPermissions(OTHER_USER, runtimePerm());
        realm.users.removeAllowPermissions(OTHER_USER, runtimePerm());
        realm.users.removeDenyPermissions(OTHER_USER, runtimePerm());
        realm.users.addAllowPermissions("nobody", runtimePerm());
        assertTrue(realm.users.get(OTHER_USER).getAllowList().isEmpty());
    }


    @Test
    public void testPermissionUpdateOnConfigUser() throws Exception
    {
        var realm = newRealm();
        realm.users.put(OTHER_USER, otherUser(OTHER_USER));

        realm.users.addAllowPermissions("user1", runtimePerm());
        assertEquals(List.of(RUNTIME_PERM), allowStrings(realm, "user1"));

        // the update is written to a complete file, without the other user
        var json = readUserFile();
        assertEquals(RUNTIME_PERM, json.getAsJsonObject("user1").getAsJsonArray("allow").get(0).getAsString());
        assertTrue(json.has("admin"));
        assertFalse(json.has(OTHER_USER));
    }


    @Test
    public void testPermissionUpdateOnUserConfigOfOtherModule() throws Exception
    {
        var realm = newRealm();
        var user = new UserConfig() {};
        user.userID = OTHER_USER;
        realm.users.put(OTHER_USER, user);

        // a user class that extends UserConfig holds run-time permissions
        realm.users.addAllowPermissions(OTHER_USER, runtimePerm());
        assertEquals(List.of(RUNTIME_PERM), allowStrings(realm, OTHER_USER));
        assertTrue(readUserFile().has(OTHER_USER));
    }


    @Test
    public void testFailedSaveKeepsFile() throws Exception
    {
        var realm = newRealm();
        realm.users.addAllowPermissions("user1", runtimePerm());
        var saved = Files.readString(userFile.toPath());

        // entry that fails when it is written
        var badUser = new UserConfig() {
            @Override
            public Collection<IPermissionPath> getAllowList()
            {
                throw new IllegalStateException("test");
            }
        };
        badUser.userID = "zzz";
        realm.users.put("zzz", badUser);

        assertThrows(SensorHubException.class, () -> realm.saveState(state));
        assertEquals(saved, Files.readString(userFile.toPath()));

        // a failed save does not fail the permission update
        realm.users.addAllowPermissions("admin", runtimePerm());
        assertEquals(saved, Files.readString(userFile.toPath()));
    }
}
