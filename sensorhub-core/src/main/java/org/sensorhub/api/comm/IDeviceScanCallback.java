/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2016 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.api.comm;


/**
 * <p>
 * Device scan callback to which newly found devices are reported during a scan
 * </p>
 *
 * @author Alex Robin
 * @since Feb 6, 2016
 */
public interface IDeviceScanCallback
{
    public void onDeviceFound(IDeviceInfo info);
    
    public void onScanError(Throwable e);
}
