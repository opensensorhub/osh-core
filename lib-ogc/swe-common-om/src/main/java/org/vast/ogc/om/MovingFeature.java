/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.vast.ogc.om;

import javax.xml.namespace.QName;
import org.vast.ogc.gml.GenericTemporalFeatureImpl;


public class MovingFeature extends GenericTemporalFeatureImpl
{
    private static final long serialVersionUID = 3975647873045316629L;
    
    public static final String MF_NS_PREFIX = "mf";
    public static final String MF_NS_URI = "http://www.opengis.net/movingfeatures/1.0";
    public static final QName QNAME = new QName(MF_NS_URI, "MovingFeature", MF_NS_PREFIX);
    
    
    @Override
    public QName getQName()
    {
        return QNAME;
    }
    
    
    @Override
    public String getType()
    {
        return QNAME.getNamespaceURI() + "/" + QNAME.getLocalPart();
    }
}