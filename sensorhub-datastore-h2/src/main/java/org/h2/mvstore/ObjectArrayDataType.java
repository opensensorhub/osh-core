package org.h2.mvstore;

import org.h2.mvstore.type.DataType;


public interface ObjectArrayDataType extends DataType<Object>
{
    @Override
    default Object[] createStorage(int size)
    {
        return new Object[size];
    }
}
