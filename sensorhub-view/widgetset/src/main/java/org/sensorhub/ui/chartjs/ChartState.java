/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2019 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.ui.chartjs;

import com.vaadin.shared.ui.JavaScriptComponentState;


public class ChartState extends JavaScriptComponentState
{
    private static final long serialVersionUID = -3108158423092855627L;
    
    public boolean newPlot;
    public boolean overlaySlider;
    public String domId;
    public String config;
    public String data = "[]";
}
