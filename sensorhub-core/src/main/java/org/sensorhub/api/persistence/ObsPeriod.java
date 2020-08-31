/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.api.persistence;


/**
 * <p>
 * Object used to store the period during which observations are available
 * for a given FOI
 * </p>
 *
 * @author Alex Robin <alex.robin@sensiasoftware.com>
 * @since Aug 30, 2020
 */
public class ObsPeriod
{
    public String foiID;
    public double begin = Double.NaN;
    public double end = Double.NaN;
    
    
    public ObsPeriod(String foiID, double begin, double end)
    {
        this.foiID = foiID;
        this.begin = begin;
        this.end = end;
    }
}
