/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.utils;

import java.nio.charset.StandardCharsets;
import org.vast.data.DataIterator;
import org.vast.swe.SWEUtils;
import org.vast.util.Asserts;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.common.hash.HashingOutputStream;
import com.google.common.io.ByteStreams;
import net.opengis.swe.v20.BlockComponent;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;
import net.opengis.swe.v20.JSONEncoding;


/**
 * <p>
 * Helper methods to check various aspects of SWE Common component structures
 * </p>
 *
 * @author Alex Robin
 * @since Sep 19, 2020
 */
public class DataComponentChecks
{
    static HashFunction hf = Hashing.goodFastHash(32);
    private static final byte[] JSON_ENCODING_HASH_MARKER =
        "SWE/2.0/JSONEncoding".getBytes(StandardCharsets.UTF_8);
    
    
    /**
     * Check if two data components have compatible structures.<br/>
     * Two components have compatible structures if the component names, types
     * and order are the same at all depth levels.
     * @param rec1
     * @param rec2
     * @return True if structures are compatible, false otherwise
     */
    public static boolean checkStructCompatible(DataComponent rec1, DataComponent rec2)
    {
        Asserts.checkNotNull(rec1, DataComponent.class);
        Asserts.checkNotNull(rec2, DataComponent.class);
        return checkStructCompatibleNullAllowed(rec1, rec2);
    }
    
    
    public static boolean checkStructCompatibleNullAllowed(DataComponent rec1, DataComponent rec2)
    {
        if (rec1 == null && rec2 == null)
            return true;
        
        if ((rec1 == null && rec2 != null) || rec1 != null && rec2 == null)
            return false;
            
        var hc1 = getStructCompatibilityHashCode(rec1);
        var hc2 = getStructCompatibilityHashCode(rec2);
        return hc1.equals(hc2);
    }
    
    
    public static HashCode getStructCompatibilityHashCode(DataComponent root)
    {
        var hasher = hf.newHasher();
        
        DataIterator it = new DataIterator(root);
        while (it.hasNext())
        {
            DataComponent c = it.next();
            if (c != root)
                hasher.putUnencodedChars(c.getName() != null ? c.getName() : "");
            hasher.putUnencodedChars(getComponentType(c.getClass()));
            if (c instanceof BlockComponent)
                hasher.putInt(c.getComponentCount());
        }
        
        return hasher.hash();
    }
    
    
    /**
     * Check if two data components are equal to each other.<br/>
     * Two components are equal if their structures are compatible and all metadata is
     * identical, including semantics, labels, descriptions and constraints.
     * @param rec1
     * @param rec2
     * @return True if structures are equal, false otherwise
     */
    public static boolean checkStructEquals(DataComponent rec1, DataComponent rec2)
    {
        Asserts.checkNotNull(rec1, DataComponent.class);
        Asserts.checkNotNull(rec2, DataComponent.class);
        return checkStructEqualsNullAllowed(rec1, rec2);
    }
    
    
    public static boolean checkStructEqualsNullAllowed(DataComponent rec1, DataComponent rec2)
    {
        if (rec1 == null && rec2 == null)
            return true;
        
        if ((rec1 == null && rec2 != null) || rec1 != null && rec2 == null)
            return false;
        
        var hc1 = getStructEqualsHashCode(rec1);
        var hc2 = getStructEqualsHashCode(rec2);
        return hc1.equals(hc2);
    }
    
    
    public static HashCode getStructEqualsHashCode(DataComponent root)
    {        
        try
        {
            var hashOs = new HashingOutputStream(hf, ByteStreams.nullOutputStream());
            new SWEUtils(SWEUtils.V2_0).writeComponent(hashOs, root, false, false);
            return hashOs.hash();
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException("Error computing hashcode. Invalid data component");
        }
    }
    
    
    public static boolean checkEncodingEquals(DataEncoding enc1, DataEncoding enc2)
    {
        Asserts.checkNotNull(enc1, DataEncoding.class);
        Asserts.checkNotNull(enc2, DataEncoding.class);
        return checkEncodingEqualsNullAllowed(enc1, enc2);
    }
    
    
    public static boolean checkEncodingEqualsNullAllowed(DataEncoding enc1, DataEncoding enc2)
    {
        if (enc1 == null && enc2 == null)
            return true;
        
        if ((enc1 == null && enc2 != null) || enc1 != null && enc2 == null)
            return false;
        
        var hc1 = getEncodingEqualsHashCode(enc1);
        var hc2 = getEncodingEqualsHashCode(enc2);
        return hc1.equals(hc2);
    }
    
    
    public static HashCode getEncodingEqualsHashCode(DataEncoding enc)
    {        
        try
        {
            var hashOs = new HashingOutputStream(hf, ByteStreams.nullOutputStream());
            writeEncodingForHash(hashOs, enc);
            return hashOs.hash();
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException("Error computing hashcode. Invalid data encoding");
        }
    }
    
    
    public static HashCode getStructEqualsHashCode(DataComponent root, DataEncoding enc)
    {        
        try
        {
            var hashOs = new HashingOutputStream(hf, ByteStreams.nullOutputStream());
            new SWEUtils(SWEUtils.V2_0).writeComponent(hashOs, root, false, false);
            writeEncodingForHash(hashOs, enc);
            return hashOs.hash();
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException("Error computing hashcode. Invalid data component");
        }
    }


    private static void writeEncodingForHash(HashingOutputStream hashOs, DataEncoding enc)
        throws Exception
    {
        if (enc instanceof JSONEncoding)
        {
            // JSONEncoding has no SWE JSON binding properties and no SWE XML
            // representation. Use a delimited family marker so equality
            // remains deterministic without changing other encodings.
            hashOs.write(0);
            hashOs.write(JSON_ENCODING_HASH_MARKER);
            hashOs.write(0);
        }
        else
            new SWEUtils(SWEUtils.V2_0).writeEncoding(hashOs, enc, false);
    }
    
    
    private static String getComponentType(Class<?> c)
    {
        for (Class<?> i: c.getInterfaces())
        {
            if (DataComponent.class.isAssignableFrom(i))
                return i.getSimpleName();
        }
        
        if (c.getSuperclass() != null)
            return getComponentType(c.getSuperclass());
        else
            return null;
    }
}
