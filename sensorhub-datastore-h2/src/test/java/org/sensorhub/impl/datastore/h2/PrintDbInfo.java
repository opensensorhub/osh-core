/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2023 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.datastore.h2;

import java.util.LinkedHashSet;
import java.util.Set;
import org.h2.mvstore.DataUtils;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.Page;


public class PrintDbInfo
{
    
    public static void main(String[] args) throws Exception
    {
        var db = new MVObsSystemDatabase();
        var config = new MVObsSystemDatabaseConfig();
        config.databaseNum = 0;
        //config.storagePath = "/home/alex/Projects/Workspace_OSH_V2/tests/db_growing_bug/piaware_austin.dat";
        config.storagePath = "/home/alex/Projects/Workspace_OSH_V2/osh-addons-grx/sensorhub-test-grx/all_obs_h2_t18.dat";
        db.init(config);
        db.start();
        
        scanPages(db.foiStore.featuresIndex);
        scanPages(db.foiStore.idsIndex);
        scanPages(db.foiStore.uidsIndex);
        //scanPages(db.foiStore.fullTextIndex.radixTreeMap);
        //scanPages(db.foiStore.spatialIndex.rTreeMap);
        
        //Thread.sleep(100000);
        db.stop();
    }
    
    
    static void scanPages(MVMap<?,?> map)
    {
        var chunkIds = new LinkedHashSet<Integer>();
        scanPage(map.getRootPage(), chunkIds);
        
        System.out.println("**************************");
        System.out.println("Map " + map.getName() + ", size=" + map.size());
        System.out.println(chunkIds);
        System.out.println();
    }
    
    
    static void scanPage(Page page, Set<Integer> chunkIds)
    {
        var pos = page.getPos();
        var chunkId = DataUtils.getPageChunkId(pos);
        chunkIds.add(chunkId);
        
        if (!page.isLeaf())
        {
            for (int i = 0; i < page.getRawChildPageCount(); i++)
            {
                var childPos = page.getChildPagePos(i);
                if (childPos != 0)
                {
                    var childPage = page.getChildPage(i);
                    scanPage(childPage, chunkIds);
                }
            }
        }
    }
}
