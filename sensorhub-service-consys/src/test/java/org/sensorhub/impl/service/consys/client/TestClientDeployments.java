/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.

Copyright (C) 2026 Sensia Software LLC. All Rights Reserved.

******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.client;

import com.google.common.base.Strings;
import org.junit.Test;
import org.sensorhub.api.system.IDeploymentWithDesc;
import org.sensorhub.impl.service.consys.resource.ResourceFormat;
import org.sensorhub.impl.service.consys.sensorml.DeploymentAdapter;
import org.vast.sensorML.SMLHelper;

import java.net.HttpURLConnection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;


public class TestClientDeployments extends TestClientBase
{

    public TestClientDeployments() {}

    TestClientDeployments(String apiRootUrl) { this.apiRootUrl = apiRootUrl; }


    @Test
    public void testAddMultipleDeployments() throws Exception {
        addDeployment(createDeployment(1), true);
        addDeployment(createDeployment(10), true);
    }

    @Test
    public void testAddDeploymentAndGetById() throws Exception {
        var deployment = createDeployment(1);
        var id = addDeployment(deployment, true);

        var result = ConSysApiClient
                .newBuilder(apiRootUrl)
                .build()
                .getDeploymentById(id, ResourceFormat.SML_JSON).get();

        assertEquals(id, result.getId());
        assertEquals(deployment.getUniqueIdentifier(), result.getUniqueIdentifier());
        assertEquals(deployment.getName(), result.getName());
        assertEquals(deployment.getDescription(), result.getDescription());
    }

    @Test
    public void testAddDeploymentAndGetByUid() throws Exception {
        var deployment = createDeployment(1);
        var id = addDeployment(deployment, true);

        var r = ConSysApiClient
                .newBuilder(apiRootUrl)
                .build()
                .getDeploymentByUid(deployment.getUniqueIdentifier(), ResourceFormat.SML_JSON).get();

        assertEquals(id, r.getId());
        assertEquals(deployment.getUniqueIdentifier(), r.getUniqueIdentifier());
        assertEquals(deployment.getName(), r.getName());
        assertEquals(deployment.getDescription(), r.getDescription());
    }


    @Test
    public void testUpdateDeployment() throws Exception
    {
        var deployment = createDeployment(2);
        var id = addDeployment(deployment, false);

        var updated = createDeployment(2);
        updated.getFullDescription().setName("Updated deployment name");
        updated.getFullDescription().setDescription("Updated deployment description");

        var statusCode = ConSysApiClient
                .newBuilder(apiRootUrl)
                .build()
                .updateDeployment(id, updated).get().intValue();

        assertEquals(HttpURLConnection.HTTP_NO_CONTENT, statusCode);

        var result = ConSysApiClient
                .newBuilder(apiRootUrl)
                .build()
                .getDeploymentById(id, ResourceFormat.SML_JSON).get();

        assertEquals(id, result.getId());
        assertEquals(updated.getUniqueIdentifier(), result.getUniqueIdentifier());
        assertEquals("Updated deployment name", result.getName());
        assertEquals("Updated deployment description", result.getDescription());
        checkDeploymentIdExists(id);
    }


    private IDeploymentWithDesc createDeployment(int num)
    {
        var deploymentUid = getDeploymentUid(num);

        var deployment = new SMLHelper().createDeployment()
            .uniqueID(deploymentUid)
            .name("Test Deployment #" + num)
            .description("Test deployment #" + num + " created using Java API client")
            .build();
        return new DeploymentAdapter(deployment);
    }

    private String addDeployment(IDeploymentWithDesc deployment, boolean checkHead) throws Exception
    {
        var id = ConSysApiClient
                .newBuilder(apiRootUrl)
                .build()
                .addDeployment(deployment).get();

        assertFalse(Strings.isNullOrEmpty(id));

        if (checkHead)
            checkDeploymentIdExists(id);

        return id;
    }

    protected String getDeploymentUid(int num)
    {
        return "urn:osh:test:deployment:" + num;
    }


    protected void checkDeploymentIdExists(String deploymentId) throws Exception
    {
        checkUriExists(apiRootUrl + "/" + DEPLOYMENT_COLLECTION + "/" + deploymentId);
    }

}
