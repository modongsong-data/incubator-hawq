package com.pivotal.pxf.plugins.hdfs.resolvers;

import com.pivotal.pxf.api.exception.BadRecordException;
import com.pivotal.pxf.api.exception.UnsupportedTypeException;
import com.pivotal.pxf.api.format.OneField;
import com.pivotal.pxf.api.format.OneRow;
import com.pivotal.pxf.api.io.DataType;
import com.pivotal.pxf.api.resolvers.ReadResolver;
import com.pivotal.pxf.api.resolvers.WriteResolver;
import com.pivotal.pxf.api.utilities.InputData;
import com.pivotal.pxf.api.utilities.Plugin;
import com.pivotal.pxf.plugins.hdfs.utilities.RecordkeyAdapter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.io.Writable;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedList;
import java.util.List;

import static com.pivotal.pxf.api.io.DataType.*;

/*
 * Class WritableResolver handles serialization and deserialization of records that were 
 * serialized using Hadoop's Writable serialization framework.
 * 
 * A field named 'recordkey' is treated as a key of the given row, and not as 
 * part of the data schema. @See RecordkeyAdapter.
 * 
 * WritableResolver implements IReadResolver and IWriteResolver interfaces.
 */
public class WritableResolver extends Plugin implements ReadResolver, WriteResolver {
    private static final int RECORDKEY_UNDEFINED = -1;
    private static final Log LOG = LogFactory.getLog(WritableResolver.class);
    private RecordkeyAdapter recordkeyAdapter = new RecordkeyAdapter();
    // reflection fields
    private Object userObject = null;
    private Field[] fields = null;

    public WritableResolver(InputData input) throws Exception {
        super(input);
        userObject = Class.forName(inputData.srlzSchemaName()).newInstance();
        fields = userObject.getClass().getDeclaredFields();

        // fields details:
        if (LOG.isDebugEnabled()) {
            for (int i = 0; i < fields.length; i++) {
                Field field = fields[i];
                String javaType = field.getType().getName();
                boolean isPrivate = Modifier.isPrivate(field.getModifiers());

                LOG.debug("Field #" + i + ", name: " + field.getName() +
                        " type: " + javaType + ", " +
                        (isArray(javaType) ? "Array" : "Primitive") + ", " +
                        (isPrivate ? "Private" : "accessible") + " field");

            }
        }
    }

    private boolean isArray(String javaType) {
        return (javaType.startsWith("[") && !"[B".equals(javaType));
    }

    /*
     * getFields returns a list of the fields of one record.
     * Each record field is represented by a OneField item.
     * OneField item contains two fields: an integer representing the field type and a Java
     * Object representing the field value.
     */
    @Override
    public List<OneField> getFields(OneRow onerow) throws Exception {
        userObject = onerow.getData();
        List<OneField> record = new LinkedList<OneField>();
        int recordkeyIndex = (inputData.getRecordkeyColumn() == null)
                ? RECORDKEY_UNDEFINED
                : inputData.getRecordkeyColumn().columnIndex();

        int currentIdx = 0;
        for (Field field : fields) {
            if (currentIdx == recordkeyIndex)
                currentIdx += recordkeyAdapter.appendRecordkeyField(record, inputData, onerow);

            if (Modifier.isPrivate(field.getModifiers()))
                continue;

            currentIdx += populateRecord(record, field);
        }

        return record;
    }

    int setArrayField(List<OneField> record, int dataType, Field reflectedField) throws IllegalAccessException {
        Object array = reflectedField.get(userObject);
        int length = Array.getLength(array);
        for (int j = 0; j < length; j++) {
            record.add(new OneField(dataType, Array.get(array, j)));
        }
        return length;
    }

    public static DataType convertJavaToGPDBType(String type) {
        if ("boolean".equals(type) || "[Z".equals(type)) {
            return BOOLEAN;
        }
        if ("int".equals(type) || "[I".equals(type)) {
            return INTEGER;
        }
        if ("double".equals(type) || "[D".equals(type)) {
            return FLOAT8;
        }
        if ("java.lang.String".equals(type) || "[Ljava.lang.String;".equals(type)) {
            return TEXT;
        }
        if ("float".equals(type) || "[F".equals(type)) {
            return REAL;
        }
        if ("long".equals(type) || "[J".equals(type)) {
            return BIGINT;
        }
        if ("[B".equals(type)) {
            return BYTEA;
        }
        if ("short".equals(type) || "[S".equals(type)) {
            return SMALLINT;
        }
        throw new UnsupportedTypeException("Type " + type + " is not supported by GPDBWritable");
    }

    int populateRecord(List<OneField> record, Field field) throws BadRecordException {
        String javaType = field.getType().getName();
        try {
            DataType dataType = convertJavaToGPDBType(javaType);
            if(isArray(javaType)){
                return setArrayField(record, dataType.getOID(), field);
            }
            record.add(new OneField(dataType.getOID(), field.get(userObject)));
            return 1;
        } catch (IllegalAccessException ex) {
            throw new BadRecordException(ex);
        }
    }

    /*
     * Reads data from inputStream and set customWritable fields.
     */
    @Override
    public OneRow setFields(List<OneField> record) throws Exception {
        int recordkeyIndex = (inputData.getRecordkeyColumn() == null)
                ? RECORDKEY_UNDEFINED
                : inputData.getRecordkeyColumn().columnIndex();
        Writable key = null;

        int colIdx = 0;
        for (Field field : fields) {
            /*
             * extract recordkey based on the column descriptor type
			 * and add to OneRow.key
			 */
            if (colIdx == recordkeyIndex) {
                key = recordkeyAdapter.convertKeyValue(record.get(colIdx).val);
                colIdx++;
            }

            if (Modifier.isPrivate(field.getModifiers())) {
                continue;
            }

            String javaType = field.getType().getName();
            convertJavaToGPDBType(javaType);
            if (isArray(javaType)) {
                Object value = field.get(userObject);
                int length = Array.getLength(value);
                for (int j = 0; j < length; j++, colIdx++) {
                    Array.set(value, j, record.get(colIdx).val);
                }
            } else {
                field.set(userObject, record.get(colIdx).val);
                colIdx++;
            }
        }

        return new OneRow(key, userObject);
    }
}
