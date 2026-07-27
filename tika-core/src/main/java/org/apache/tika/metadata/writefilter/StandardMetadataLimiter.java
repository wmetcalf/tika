/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tika.metadata.writefilter;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.tika.metadata.AccessPermissions;
import org.apache.tika.metadata.Barcode;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.MetadataRecord;
import org.apache.tika.metadata.Office;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.utils.StringUtils;

/**
 * Standard implementation of {@link MetadataWriteLimiter} that limits the amount of metadata
 * a parser can add based on {@link #maxTotalEstimatedSize}, {@link #maxFieldSize},
 * {@link #maxValuesPerField}, and {@link #maxKeySize}. This can also be used to limit which
 * fields are stored in the metadata object at write-time with {@link #includeFields}.
 *
 * <p>All sizes are measured in UTF-16 bytes. The size is estimated as a rough order of magnitude
 * of what is required to store the string in memory in Java. We recognize that Java uses more
 * bytes to store length, offset etc. for strings, but the extra overhead varies by Java version
 * and implementation, and we just need a basic estimate. We also recognize actual memory usage
 * is affected by interning strings, etc.
 * Please forgive us ... or consider writing your own limiter. :)
 *
 * <p><b>NOTE:</b> Fields in {@link #ALWAYS_SET_FIELDS} are always set no matter the current
 * state of {@link #maxTotalEstimatedSize}. Except for {@link TikaCoreProperties#TIKA_CONTENT},
 * they are truncated at {@link #maxFieldSize}, and their sizes contribute to the
 * {@link #maxTotalEstimatedSize}.
 *
 * <p><b>NOTE:</b> Fields in {@link #ALWAYS_ADD_FIELDS} are always added no matter the current
 * state of {@link #maxTotalEstimatedSize}. Except for {@link TikaCoreProperties#TIKA_CONTENT},
 * each addition is truncated at {@link #maxFieldSize}, and their sizes contribute to the
 * {@link #maxTotalEstimatedSize}.
 *
 * <p>This class uses {@link #minimumMaxFieldSizeInAlwaysFields} to protect the
 * {@link #ALWAYS_ADD_FIELDS} and {@link #ALWAYS_SET_FIELDS}. If we didn't have this and a user
 * sets the {@link #maxFieldSize} to, say, 10 bytes, the internal parser behavior would be broken
 * because parsers rely on {@link Metadata#CONTENT_TYPE} to determine which parser to call.
 *
 * <p><b>NOTE:</b> as with {@link Metadata}, this object is not thread safe.
 *
 * @since Apache Tika 4.0 (renamed from StandardWriteFilter)
 */
public class StandardMetadataLimiter implements MetadataWriteLimiter, Serializable {

    private static final long serialVersionUID = 8628340516372080931L;

    public static final Set<String> ALWAYS_SET_FIELDS = new HashSet<>();
    public static final Set<String> ALWAYS_ADD_FIELDS = new HashSet<>();

    /**
     * Multi-valued structured fields whose individual values must never be truncated.
     * A value that does not fit is dropped as a whole and metadata is marked truncated.
     */
    public static final Set<String> ATOMIC_ADD_FIELDS = Set.of(
            "msoffice:link:record",
            "barcode:record",
            MetadataRecord.PPKG_DATA_ASSET_RECORD,
            "ppkg:embedded_file_sha256",
            "ppkg:embedded_file_md5",
            "ppkg:embedded_file_sha1",
            "ppkg:embedded_file_name",
            "ppkg:embedded_file_size",
            "ppkg:embedded_file_mime");

    /**
     * Compatibility fields that represent parallel records. Empty placeholders must
     * survive filtering or later values become associated with the wrong record.
     */
    public static final Set<String> ALIGNED_ADD_FIELDS = Set.of(
            Office.OFFICE_LINK_URL.getName(),
            Office.OFFICE_LINK_TYPE.getName(),
            Office.OFFICE_LINK_TEXT.getName(),
            Office.OFFICE_LINK_OCR_TEXT.getName(),
            Office.OFFICE_LINK_SOURCE.getName(),
            Office.OFFICE_LINK_CONTEXT.getName(),
            Office.OFFICE_LINK_RELATIONSHIP_TYPE.getName(),
            Office.OFFICE_LINK_ID.getName(),
            Office.OFFICE_LINK_TRIGGER.getName(),
            Office.OFFICE_LINK_ACTION_TYPE.getName(),
            Barcode.BARCODE_VALUE.getName(),
            Barcode.BARCODE_FORMAT.getName(),
            Barcode.BARCODE_RAW_BYTES.getName(),
            Barcode.BARCODE_POSITION.getName(),
            Barcode.BARCODE_ERROR_CORRECTION_LEVEL.getName(),
            Barcode.BARCODE_IS_MIRRORED.getName(),
            "ppkg:embedded_file_sha256",
            "ppkg:embedded_file_md5",
            "ppkg:embedded_file_sha1",
            "ppkg:embedded_file_name",
            "ppkg:embedded_file_size",
            "ppkg:embedded_file_mime");

    private static final List<List<String>> ALIGNED_FIELD_GROUPS = List.of(
            List.of(
                    Office.OFFICE_LINK_URL.getName(),
                    Office.OFFICE_LINK_TYPE.getName(),
                    Office.OFFICE_LINK_TEXT.getName(),
                    Office.OFFICE_LINK_OCR_TEXT.getName(),
                    Office.OFFICE_LINK_SOURCE.getName(),
                    Office.OFFICE_LINK_CONTEXT.getName(),
                    Office.OFFICE_LINK_RELATIONSHIP_TYPE.getName(),
                    Office.OFFICE_LINK_ID.getName(),
                    Office.OFFICE_LINK_TRIGGER.getName(),
                    Office.OFFICE_LINK_ACTION_TYPE.getName()),
            List.of(
                    Barcode.BARCODE_VALUE.getName(),
                    Barcode.BARCODE_FORMAT.getName(),
                    Barcode.BARCODE_RAW_BYTES.getName(),
                    Barcode.BARCODE_POSITION.getName(),
                    Barcode.BARCODE_ERROR_CORRECTION_LEVEL.getName(),
                    Barcode.BARCODE_IS_MIRRORED.getName()),
            List.of(
                    "ppkg:embedded_file_sha256",
                    "ppkg:embedded_file_md5",
                    "ppkg:embedded_file_sha1",
                    "ppkg:embedded_file_name",
                    "ppkg:embedded_file_size",
                    "ppkg:embedded_file_mime"));

    static {
        ALWAYS_SET_FIELDS.add(Metadata.CONTENT_LENGTH);
        ALWAYS_SET_FIELDS.add(Metadata.CONTENT_TYPE);
        ALWAYS_SET_FIELDS.add(Metadata.CONTENT_ENCODING);
        ALWAYS_SET_FIELDS.add(TikaCoreProperties.CONTENT_TYPE_USER_OVERRIDE.getName());
        ALWAYS_SET_FIELDS.add(TikaCoreProperties.CONTENT_TYPE_PARSER_OVERRIDE.getName());
        ALWAYS_SET_FIELDS.add(TikaCoreProperties.CONTENT_TYPE_HINT.getName());
        ALWAYS_SET_FIELDS.add(TikaCoreProperties.TIKA_CONTENT.getName());
        ALWAYS_SET_FIELDS.add(TikaCoreProperties.RESOURCE_NAME_KEY.getName());
        ALWAYS_SET_FIELDS.add(AccessPermissions.EXTRACT_CONTENT.getName());
        ALWAYS_SET_FIELDS.add(AccessPermissions.EXTRACT_FOR_ACCESSIBILITY.getName());
        ALWAYS_SET_FIELDS.add(Metadata.CONTENT_DISPOSITION);
        ALWAYS_SET_FIELDS.add(TikaCoreProperties.CONTAINER_EXCEPTION.getName());
        ALWAYS_SET_FIELDS.add(TikaCoreProperties.EMBEDDED_EXCEPTION.getName());
        //Metadata.CONTENT_LOCATION? used by the html parser
    }

    static {
        ALWAYS_ADD_FIELDS.add(TikaCoreProperties.TIKA_PARSED_BY.getName());
    }

    private static final String METADATA_TRUNCATED_KEY =
            TikaCoreProperties.TRUNCATED_METADATA.getName();
    private static final String TIKA_CONTENT_KEY = TikaCoreProperties.TIKA_CONTENT.getName();
    private static final String[] TRUE = new String[]{"true"};

    //allow at least these many bytes in the "always" fields.
    //As of 2022-03, the longest mime is 146.  Doubling that gives
    //us some leeway.  If a mime is truncated, bad things will happen.
    private final int minimumMaxFieldSizeInAlwaysFields = 300;


    private final boolean includeEmpty;
    private final int maxTotalEstimatedSize;
    private final int maxValuesPerField;
    private final int maxFieldSize;
    private final int maxKeySize;


    private final Set<String> includeFields;
    private final Set<String> excludeFields;

    private Map<String, Integer> fieldSizes = new HashMap<>();
    private Set<String> suppressedAlignedGroups = new HashSet<>();
    private Map<String, Integer> removedAlignedFieldCounts = new HashMap<>();
    private Map<String, String> truncatedFieldSources = new HashMap<>();
    private Set<String> legacyFieldsWithUnknownProvenance = new HashSet<>();

    //tracks the estimated size in utf16 bytes. Can be > maxEstimated size
    int estimatedSize = 0;

    /**
     * @param maxKeySize maximum key size in UTF-16 bytes-- keys will be truncated to this
     *                   length; if less than 0, keys will not be truncated
     * @param maxEstimatedSize maximum total estimated size in UTF-16 bytes
     * @param includeFields if null or empty, all fields are included; otherwise, which fields
     *                      to add to the metadata object.
     * @param excludeFields these fields will not be included (unless they're in {@link StandardMetadataLimiter#ALWAYS_SET_FIELDS})
     * @param includeEmpty if <code>true</code>, this will set or add an empty value to the
     *                     metadata object.
     */
    protected StandardMetadataLimiter(int maxKeySize, int maxFieldSize, int maxEstimatedSize,
                               int maxValuesPerField,
                               Set<String> includeFields,
                               Set<String> excludeFields,
                               boolean includeEmpty) {

        this.maxKeySize = maxKeySize;
        this.maxFieldSize = maxFieldSize;
        this.maxTotalEstimatedSize = maxEstimatedSize;
        this.maxValuesPerField = maxValuesPerField;
        // Defensive copies to prevent external modification
        this.includeFields = includeFields != null ? new HashSet<>(includeFields) : new HashSet<>();
        this.excludeFields = excludeFields != null ? new HashSet<>(excludeFields) : new HashSet<>();
        this.includeEmpty = includeEmpty;
    }

    private void readObject(ObjectInputStream input)
            throws IOException, ClassNotFoundException {
        input.defaultReadObject();
        if (fieldSizes == null) {
            fieldSizes = new HashMap<>();
        }
        if (suppressedAlignedGroups == null) {
            suppressedAlignedGroups = new HashSet<>();
        }
        if (removedAlignedFieldCounts == null) {
            removedAlignedFieldCounts = new HashMap<>();
        }
        if (truncatedFieldSources == null) {
            truncatedFieldSources = new HashMap<>();
            legacyFieldsWithUnknownProvenance = new HashSet<>(fieldSizes.keySet());
        }
        if (legacyFieldsWithUnknownProvenance == null) {
            legacyFieldsWithUnknownProvenance = new HashSet<>();
        }
    }

    @Override
    public void set(String field, String value, Map<String, String[]> data) {
        //legacy behavior is that setting(null) removes the key
        if (value == null) {
            remove(field, data);
            return;
        }
        if (! include(field, value)) {
            return;
        }
        if (ALWAYS_SET_FIELDS.contains(field) || ALWAYS_ADD_FIELDS.contains(field)) {
            setAlwaysInclude(field, value, data);
            return;
        }
        if (isOversizedAtomicKey(field)) {
            setTruncated(data);
            return;
        }
        if (!prepareAlignedGroup(field, data)) {
            return;
        }

        StringSizePair filterKey = filterKey(field, value, data);
        if (ATOMIC_ADD_FIELDS.contains(field) || ALIGNED_ADD_FIELDS.contains(field)) {
            setAtomic(filterKey, value, data);
            return;
        }
        setFilterKey(filterKey, value, data);
    }

    @Override
    public void remove(String field, Map<String, String[]> data) {
        String storedField = field;
        if (!ALWAYS_SET_FIELDS.contains(field)
                && !ALWAYS_ADD_FIELDS.contains(field)
                && maxKeySize >= 0
                && estimateSize(field) > maxKeySize) {
            storedField = truncateValue(field, maxKeySize);
            String source = truncatedFieldSources.get(storedField);
            if (source == null
                    && !legacyFieldsWithUnknownProvenance.contains(storedField)
                    || source != null && !field.equals(source)) {
                return;
            }
        }
        String[] removed = data.remove(storedField);
        Integer trackedValueSize = fieldSizes.remove(storedField);
        truncatedFieldSources.remove(storedField);
        legacyFieldsWithUnknownProvenance.remove(storedField);
        if (removed != null && ALIGNED_ADD_FIELDS.contains(storedField)) {
            removedAlignedFieldCounts.put(storedField, removed.length);
        }
        if (trackedValueSize != null) {
            estimatedSize = Math.max(0,
                    estimatedSize - trackedValueSize - estimateSize(storedField));
            return;
        }
        if (removed == null || TIKA_CONTENT_KEY.equals(storedField)
                || (!ALWAYS_SET_FIELDS.contains(storedField)
                && !ALWAYS_ADD_FIELDS.contains(storedField))) {
            return;
        }
        int removedSize = estimateSize(storedField);
        for (String value : removed) {
            if (value != null) {
                removedSize += estimateSize(value);
            }
        }
        estimatedSize = Math.max(0, estimatedSize - removedSize);
    }

    @Override
    public void replace(String field, String[] values, Map<String, String[]> data) {
        remove(field, data);
        Integer removedAlignedCount = removedAlignedFieldCounts.remove(field);
        if (values != null) {
            for (String value : values) {
                if (value != null) {
                    add(field, value, data);
                }
            }
        }
        String[] retainedValues = data.get(field);
        int retainedCount = retainedValues == null ? 0 : retainedValues.length;
        if (removedAlignedCount != null && retainedCount != removedAlignedCount) {
            removedAlignedFieldCounts.put(field,
                    Math.max(removedAlignedCount, retainedCount));
        }
    }

    private void setAlwaysInclude(String field, String value, Map<String, String[]> data) {
        if (TIKA_CONTENT_KEY.equals(field)) {
            data.put(field, new String[]{ value });
            clearFieldSource(field);
            return;
        }
        int sizeToAdd = estimateSize(value);
        //if the maxFieldSize is < minimumMaxFieldSizeInAlwaysFields, use the minmax
        //we do not want to truncate a mime!
        int alwaysMaxFieldLength = Math.max(minimumMaxFieldSizeInAlwaysFields, maxFieldSize);
        String toSet = value;
        if (sizeToAdd > alwaysMaxFieldLength) {
            toSet = truncate(value, alwaysMaxFieldLength, data);
            sizeToAdd = estimateSize(toSet);
        }
        int totalAdded = data.containsKey(field) ? 0 : estimateSize(field);
        totalAdded += sizeToAdd;
        if (data.containsKey(field)) {
            String[] vals = data.get(field);
            //this should only ever be single valued!!!
            if (vals.length > 0) {
                totalAdded -= estimateSize(vals[0]);
            }
        }
        estimatedSize += totalAdded;
        data.put(field, new String[]{toSet});
        clearFieldSource(field);
    }

    private void addAlwaysInclude(String field, String value, Map<String, String[]> data) {
        if (TIKA_CONTENT_KEY.equals(field)) {
            data.put(field, new String[]{ value });
            clearFieldSource(field);
            return;
        }
        if (! data.containsKey(field)) {
            setAlwaysInclude(field, value, data);
            return;
        }
        //TODO: should we limit the number of field values?

        int toAddSize = estimateSize(value);
        //if the maxFieldSize is < minimumMaxFieldSizeInAlwaysFields, use the minmax
        //we do not want to truncate a mime!
        int alwaysMaxFieldLength = Math.max(minimumMaxFieldSizeInAlwaysFields, maxFieldSize);
        String toAddValue = value;
        if (toAddSize > alwaysMaxFieldLength) {
            toAddValue = truncate(value, alwaysMaxFieldLength, data);
            toAddSize = estimateSize(toAddValue);
        }
        int totalAdded = data.containsKey(field) ? 0 : estimateSize(field);
        totalAdded += toAddSize;
        estimatedSize += totalAdded;

        data.put(field, appendValue(data.get(field), toAddValue));
        clearFieldSource(field);
    }


    //calculate the max field length allowed if we are
    //setting a value
    private int maxAllowedToSet(StringSizePair filterKey) {
        Integer existingSizeInt = fieldSizes.get(filterKey.string);
        int existingSize = existingSizeInt == null ? 0 : existingSizeInt;

        //this is how much is allowed by the overall total limit
        int allowedByMaxTotal = maxTotalEstimatedSize - estimatedSize;

        //if we're overwriting a value, that value's data size is now available
        allowedByMaxTotal += existingSize;

        //if we're adding a key, we need to subtract that value
        allowedByMaxTotal -= existingSizeInt == null ? filterKey.size : 0;

        return Math.min(maxFieldSize, allowedByMaxTotal);
    }


    @Override
    public void add(String field, String value, Map<String, String[]> data) {
        if (! include(field, value)) {
            return;
        }
        if (ALWAYS_SET_FIELDS.contains(field)) {
            setAlwaysInclude(field, value, data);
            return;
        } else if (ALWAYS_ADD_FIELDS.contains(field)) {
            addAlwaysInclude(field, value, data);
            return;
        }
        if (isOversizedAtomicKey(field)) {
            setTruncated(data);
            return;
        }
        if (!prepareAlignedGroup(field, data)) {
            return;
        }
        StringSizePair filterKey = filterKey(field, value, data);
        if (ATOMIC_ADD_FIELDS.contains(field) || ALIGNED_ADD_FIELDS.contains(field)) {
            addAtomic(filterKey, value, data);
            return;
        }
        if (! data.containsKey(filterKey.string)) {
            setFilterKey(filterKey, value, data);
            return;
        }

        String[] vals = data.get(filterKey.string);

        if (vals != null && vals.length >= maxValuesPerField) {
            setTruncated(data);
            return;
        }

        Integer fieldSizeInteger = fieldSizes.get(filterKey.string);
        int fieldSize = fieldSizeInteger == null ? 0 : fieldSizeInteger;
        int maxAllowed = maxAllowedToAdd(filterKey);
        // if == 1, then only one byte of two would be allowed, which lands us in "", so needs to be <=
        if (maxAllowed <= 1) {
            setTruncated(data);
            return;
        }
        int valueLength = estimateSize(value);
        String toAdd = value;
        if (valueLength > maxAllowed) {
            toAdd = truncate(value, maxAllowed, data);
            valueLength = estimateSize(toAdd);
            if (valueLength == 0) {
                return;
            }
        }

        int addedOverall = valueLength;
        if (fieldSizeInteger == null) {
            //if there was no value before, we're adding
            //a key.  If there was a value before, do not
            //add the key length.
            addedOverall += filterKey.size;
        }
        estimatedSize += addedOverall;

        fieldSizes.put(filterKey.string, valueLength + fieldSize);

        data.put(filterKey.string, appendValue(data.get(filterKey.string), toAdd ));
        recordFieldSource(filterKey);
    }

    @Override
    public void addFirst(String field, String value, Map<String, String[]> data) {
        if (removedAlignedFieldCounts.containsKey(field)) {
            add(field, value, data);
        } else {
            set(field, value, data);
        }
    }

    /**
     * The first member emitted by each compatibility helper is the record boundary.
     * Reserve every included member key at that boundary, or suppress the whole
     * group. Once the keys are admitted, {@link #addAlignedPlaceholder} can always
     * append a zero-cost placeholder when a later value no longer fits.
     */
    private boolean prepareAlignedGroup(String field, Map<String, String[]> data) {
        List<String> group = null;
        for (List<String> candidate : ALIGNED_FIELD_GROUPS) {
            if (candidate.contains(field)) {
                group = candidate;
                break;
            }
        }
        if (group == null) {
            return true;
        }

        String groupId = group.get(0);
        String boundaryField = null;
        for (String member : group) {
            if (includeField(member)) {
                boundaryField = member;
                break;
            }
        }
        if (suppressedAlignedGroups.contains(groupId)) {
            if (!field.equals(boundaryField)) {
                return false;
            }
            // A failed reservation suppresses the remainder of that logical
            // record. The next boundary begins a new record and must retry:
            // callers may have removed metadata and released total budget.
            suppressedAlignedGroups.remove(groupId);
        }
        if (boundaryField == null) {
            return true;
        }

        int requiredKeyBytes = 0;
        for (String member : group) {
            if (!includeField(member) || fieldSizes.containsKey(member)) {
                continue;
            }
            int keyBytes = estimateSize(member);
            if (maxKeySize >= 0 && keyBytes > maxKeySize
                    || keyBytes > maxTotalEstimatedSize - estimatedSize - requiredKeyBytes) {
                suppressedAlignedGroups.add(groupId);
                setTruncated(data);
                return false;
            }
            requiredKeyBytes += keyBytes;
        }

        for (String member : group) {
            if (!includeField(member)) {
                continue;
            }
            if (!fieldSizes.containsKey(member)) {
                fieldSizes.put(member, 0);
            }
        }
        estimatedSize += requiredKeyBytes;

        // HashMap-backed metadata merge paths do not guarantee that the record
        // boundary field arrives first. Reserving every member key above must
        // therefore happen for any member; replacement alignment remains tied
        // to the boundary that starts the logical record.
        if (!field.equals(boundaryField)) {
            return true;
        }

        String[] boundaryValues = data.get(boundaryField);
        int priorRecordCount = boundaryValues == null ? 0 : boundaryValues.length;
        boolean groupHasReplacementCount = false;
        for (String member : group) {
            if (!includeField(member)) {
                continue;
            }
            Integer replacementCount = removedAlignedFieldCounts.get(member);
            if (replacementCount != null) {
                groupHasReplacementCount = true;
                priorRecordCount = Math.max(priorRecordCount, replacementCount);
            }
        }

        for (String member : group) {
            if (!includeField(member)) {
                continue;
            }
            String[] values = data.get(member);
            int valueCount = values == null ? 0 : values.length;
            if (groupHasReplacementCount && valueCount < priorRecordCount) {
                String[] aligned = values == null
                        ? new String[priorRecordCount]
                        : Arrays.copyOf(values, priorRecordCount);
                Arrays.fill(aligned, valueCount, priorRecordCount, "");
                data.put(member, aligned);
            }
            removedAlignedFieldCounts.remove(member);
        }
        return true;
    }

    private void addAtomic(StringSizePair filterKey, String value,
                           Map<String, String[]> data) {
        String[] values = data.get(filterKey.string);
        if (values != null && values.length >= maxValuesPerField) {
            setTruncated(data);
            return;
        }

        Integer fieldSizeValue = fieldSizes.get(filterKey.string);
        int fieldSize = fieldSizeValue == null ? 0 : fieldSizeValue;
        int keySize = fieldSizeValue == null ? filterKey.size : 0;
        int allowedByField = maxFieldSize - fieldSize;
        int allowedByTotal = maxTotalEstimatedSize - estimatedSize - keySize;
        int valueSize = estimateSize(value);
        if (valueSize > Math.min(allowedByField, allowedByTotal)) {
            setTruncated(data);
            addAlignedPlaceholder(filterKey, values, keySize, data);
            return;
        }

        estimatedSize += keySize + valueSize;
        fieldSizes.put(filterKey.string, fieldSize + valueSize);
        if (values == null) {
            data.put(filterKey.string, new String[]{value});
        } else {
            data.put(filterKey.string, appendValue(values, value));
        }
    }

    private void addAlignedPlaceholder(StringSizePair filterKey, String[] values,
                                       int keySize, Map<String, String[]> data) {
        if (!ALIGNED_ADD_FIELDS.contains(filterKey.string)) {
            return;
        }
        if (values == null) {
            if (keySize > maxTotalEstimatedSize - estimatedSize) {
                return;
            }
            estimatedSize += keySize;
            fieldSizes.put(filterKey.string, 0);
            data.put(filterKey.string, new String[]{""});
        } else {
            data.put(filterKey.string, appendValue(values, ""));
        }
    }

    private void setAtomic(StringSizePair filterKey, String value,
                           Map<String, String[]> data) {
        Integer existingSizeValue = fieldSizes.get(filterKey.string);
        int existingSize = existingSizeValue == null ? 0 : existingSizeValue;
        int keySize = existingSizeValue == null ? filterKey.size : 0;
        int allowedByTotal =
                maxTotalEstimatedSize - estimatedSize + existingSize - keySize;
        int valueSize = estimateSize(value);
        if (valueSize > Math.min(maxFieldSize, allowedByTotal)) {
            if (ALIGNED_ADD_FIELDS.contains(filterKey.string)) {
                if (keySize > maxTotalEstimatedSize - estimatedSize) {
                    setTruncated(data);
                    return;
                }
                estimatedSize += keySize - existingSize;
                fieldSizes.put(filterKey.string, 0);
                data.put(filterKey.string, new String[]{""});
                setTruncated(data);
                return;
            }
            remove(filterKey.string, data);
            setTruncated(data);
            return;
        }

        estimatedSize += keySize + valueSize - existingSize;
        fieldSizes.put(filterKey.string, valueSize);
        data.put(filterKey.string, new String[]{value});
    }

    private String[] appendValue(String[] values, final String value) {
        if (value == null) {
            return values;
        }
        String[] newValues = new String[values.length + 1];
        System.arraycopy(values, 0, newValues, 0, values.length);
        newValues[newValues.length - 1] = value;
        return newValues;
    }

    //calculate the max field length allowed if we are
    //adding a value
    private int maxAllowedToAdd(StringSizePair filterKey) {
        Integer existingSizeInt = fieldSizes.get(filterKey.string);
        int existingSize = existingSizeInt == null ? 0 : existingSizeInt;
        //how much can we add to this field
        int allowedByMaxField = maxFieldSize - existingSize;

        //this is how much is allowed by the overall total limit
        int allowedByMaxTotal = maxTotalEstimatedSize - estimatedSize - 1;

        //if we're adding a new key, we need to subtract that value
        allowedByMaxTotal -= existingSizeInt == null ? filterKey.size : 0;

        return Math.min(allowedByMaxField, allowedByMaxTotal);
    }

    private void setFilterKey(StringSizePair filterKey, String value,
                              Map<String, String[]> data) {
        //if you can't even add the key, give up now
        if (! data.containsKey(filterKey.string) &&
                (filterKey.size + estimatedSize > maxTotalEstimatedSize)) {
            setTruncated(data);
            return;
        }

        Integer fieldSizeInteger = fieldSizes.get(filterKey.string);
        int fieldSize = fieldSizeInteger == null ? 0 : fieldSizeInteger;
        int maxAllowed = maxAllowedToSet(filterKey);
        if (maxAllowed <= 0) {
            setTruncated(data);
            return;
        }
        int valueLength = estimateSize(value);
        String toSet = value;
        if (valueLength > maxAllowed) {
            toSet = truncate(value, maxAllowed, data);
            valueLength = estimateSize(toSet);
            if (valueLength == 0) {
                return;
            }
        }

        int addedOverall = 0;
        if (fieldSizeInteger == null) {
            //if there was no value before, we're adding
            //a key.  If there was a value before, do not
            //add the key length.
            addedOverall += filterKey.size;
        }
        addedOverall += valueLength - fieldSize;
        estimatedSize += addedOverall;

        fieldSizes.put(filterKey.string, valueLength);

        data.put(filterKey.string, new String[]{ toSet });
        recordFieldSource(filterKey);

    }

    private void setTruncated(Map<String, String[]> data) {
        data.put(METADATA_TRUNCATED_KEY, TRUE);
    }

    private StringSizePair filterKey(String field, String value, Map<String, String[]> data) {
        int size = estimateSize(field);
        if (maxKeySize < 0 || size <= maxKeySize) {
            return new StringSizePair(field, size, false, field);
        }

        String toWrite = truncate(field, maxKeySize, data);
        return new StringSizePair(toWrite,
                estimateSize(toWrite),
                true,
                field);
    }

    private void recordFieldSource(StringSizePair filterKey) {
        legacyFieldsWithUnknownProvenance.remove(filterKey.string);
        if (filterKey.truncated) {
            truncatedFieldSources.put(filterKey.string, filterKey.source);
        } else {
            truncatedFieldSources.remove(filterKey.string);
        }
    }

    private void clearFieldSource(String field) {
        truncatedFieldSources.remove(field);
        legacyFieldsWithUnknownProvenance.remove(field);
    }

    private boolean isOversizedAtomicKey(String field) {
        return ATOMIC_ADD_FIELDS.contains(field)
                && maxKeySize >= 0
                && estimateSize(field) > maxKeySize;
    }

    private String truncate(String value, int length, Map<String, String[]> data) {
        setTruncated(data);
        return truncateValue(value, length);
    }

    private String truncateValue(String value, int length) {
        //correctly handle multibyte characters
        byte[] bytes = value.getBytes(StandardCharsets.UTF_16BE);
        ByteBuffer bb = ByteBuffer.wrap(bytes, 0, length);
        CharBuffer cb = CharBuffer.allocate(length);
        CharsetDecoder decoder = StandardCharsets.UTF_16BE.newDecoder();
        // Ignore last (potentially) incomplete character
        decoder.onMalformedInput(CodingErrorAction.IGNORE);
        decoder.decode(bb, cb, true);
        decoder.flush(cb);
        return new String(cb.array(), 0, cb.position());
    }

    private boolean include(String field, String value) {
        return includeField(field) &&
                (isAlignedPlaceholder(field, value) || includeValue(value));
    }

    private boolean isAlignedPlaceholder(String field, String value) {
        return value != null && StringUtils.isBlank(value) && ALIGNED_ADD_FIELDS.contains(field);
    }

    /**
     * Tests for null or empty. Does not check for length
     * @param value
     * @return
     */
    private boolean includeValue(String value) {
        if (includeEmpty) {
            return true;
        }
        if (StringUtils.isBlank(value)) {
            return false;
        }
        return true;
    }

    private boolean includeField(String name) {
        if (name == null) {
            throw new NullPointerException("property name must not be null");
        }
        // Check both ALWAYS_SET_FIELDS and ALWAYS_ADD_FIELDS - these must always pass
        if (ALWAYS_SET_FIELDS.contains(name) || ALWAYS_ADD_FIELDS.contains(name)) {
            return true;
        }
        if (excludeFields.contains(name)) {
            return false;
        }
        if (MetadataRecord.containsAnyField(name, excludeFields)) {
            return false;
        }
        return includeFields.isEmpty() || includeFields.contains(name);
    }

    private static int estimateSize(String s) {
        if (s == null) {
            return 0;
        }
        return 2 * s.length();
    }

    private static class StringSizePair {
        final String string;
        final int size;//utf-16 bytes -- estimated
        final boolean truncated;
        final String source;

        public StringSizePair(String string, int size, boolean truncated,
                              String source) {
            this.string = string;
            this.size = size;
            this.truncated = truncated;
            this.source = source;
        }
    }
}
