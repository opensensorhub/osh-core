/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2019 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.procedure;

import org.sensorhub.api.obs.IObsStore;
import org.sensorhub.api.procedure.IProcedureDescStore;
import org.sensorhub.api.procedure.IProcedureDescStore.ProcedureField;
import net.opengis.sensorml.v20.AbstractProcess;


/**
 * <p>
 * In-memory implementation of procedure store backed by a {@link java.util.NavigableMap}.
 * This implementation is only used to store the latest procedure state and thus
 * doesn't support procedure description history.
 * </p>
 *
 * @author Alex Robin
 * @date Sep 28, 2019
 */
public class InMemoryProcedureStore extends InMemoryFeatureStore<AbstractProcess, ProcedureField> implements IProcedureDescStore
{
    IObsStore obsStore;
    
    
    @Override
    public void linkTo(IObsStore obsStore)
    {
        this.obsStore = obsStore;
    }
}