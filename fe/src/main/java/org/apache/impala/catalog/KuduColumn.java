// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.impala.catalog;

import com.google.common.base.Preconditions;
import org.apache.impala.analysis.LiteralExpr;
import org.apache.impala.common.AnalysisException;
import org.apache.impala.common.ImpalaRuntimeException;
import org.apache.impala.thrift.TColumn;
import org.apache.impala.util.KuduUtil;

import org.apache.kudu.ColumnSchema.CompressionAlgorithm;
import org.apache.kudu.ColumnSchema.Encoding;
import org.apache.kudu.ColumnSchema;
import org.apache.kudu.Schema;

/**
 *  Represents a Kudu column.
 *
 *  This class extends Column with Kudu-specific information:
 *  - primary key
 *  - primary key unique
 *  - nullability constraint
 *  - auto_incrementing
 *  - encoding
 *  - compression
 *  - default value
 *  - desired block size
 */
public class KuduColumn extends Column {

  // The name of the column as it appears in Kudu, i.e. not converted to lower case.
  private final String kuduName_;
  private final boolean isKey_;
  private final boolean isPrimaryKeyUnique_;
  private final boolean isNullable_;
  private final boolean isAutoIncrementing_;
  private final Encoding encoding_;
  private final CompressionAlgorithm compression_;
  private final int blockSize_;

  // Default value for this column. The expr is a literal of the target column type
  // post-analysis. For TIMESTAMPs those are BIGINT values storing the unix time in
  // microseconds. Code that references this may need to handle TIMESTAMP specially.
  // For that reason, this isn't exposed publicly, e.g. getDefaultValueSql() is used
  // to hide this complexity externally.
  private final LiteralExpr defaultValue_;

  private KuduColumn(String name, Type type, boolean isKey, boolean isPrimaryKeyUnique,
      boolean isNullable, boolean isAutoIncrementing, Encoding encoding,
      CompressionAlgorithm compression, LiteralExpr defaultValue, int blockSize,
      String comment, int position) {
    super(name.toLowerCase(), type, comment, position);
    Preconditions.checkArgument(defaultValue == null || type == defaultValue.getType()
        || (type.isTimestamp() && defaultValue.getType().isIntegerType()));
    if (isKey) {
      Preconditions.checkArgument(!isPrimaryKeyUnique || !isAutoIncrementing);
    } else {
      Preconditions.checkArgument(!isPrimaryKeyUnique && !isAutoIncrementing);
    }
    kuduName_ = name;
    isKey_ = isKey;
    isPrimaryKeyUnique_ = isPrimaryKeyUnique;
    isNullable_ = isNullable;
    isAutoIncrementing_ = isAutoIncrementing;
    encoding_ = encoding;
    compression_ = compression;
    defaultValue_ = defaultValue;
    blockSize_ = blockSize;
  }

  public static KuduColumn fromColumnSchema(ColumnSchema colSchema, int position)
      throws ImpalaRuntimeException {
    Type type = KuduUtil.toImpalaType(colSchema.getType(), colSchema.getTypeAttributes());
    Object defaultValue = colSchema.getDefaultValue();
    LiteralExpr defaultValueExpr = null;
    if (defaultValue != null) {
      Type defaultValueType = type.isTimestamp() ? Type.BIGINT : type;
      try {
        defaultValueExpr = LiteralExpr.createFromUnescapedStr(defaultValue.toString(),
            defaultValueType);
      } catch (AnalysisException e) {
        throw new ImpalaRuntimeException(String.format("Error parsing default value: " +
            "'%s'", defaultValue), e);
      }
      Preconditions.checkNotNull(defaultValueExpr);
    }
    String comment = !colSchema.getComment().isEmpty() ? colSchema.getComment() : null;
    return new KuduColumn(colSchema.getName(), type, colSchema.isKey(),
        colSchema.isKeyUnique(), colSchema.isNullable(), colSchema.isAutoIncrementing(),
        colSchema.getEncoding(), colSchema.getCompressionAlgorithm(), defaultValueExpr,
        colSchema.getDesiredBlockSize(), comment, position);
  }

  public static KuduColumn fromThrift(TColumn column, int position)
      throws ImpalaRuntimeException {
    Preconditions.checkState(column.isSetIs_key());
    Preconditions.checkState(column.isSetIs_primary_key_unique());
    boolean isNullable = false;
    if (column.isSetIs_nullable()) isNullable = column.isIs_nullable();
    Type columnType = Type.fromThrift(column.getColumnType());
    Encoding encoding = null;
    if (column.isSetEncoding()) encoding = KuduUtil.fromThrift(column.getEncoding());
    CompressionAlgorithm compression = null;
    if (column.isSetCompression()) {
      compression = KuduUtil.fromThrift(column.getCompression());
    }
    LiteralExpr defaultValue = null;
    if (column.isSetDefault_value()) {
      Type defaultValueType = columnType.isTimestamp() ? Type.BIGINT : columnType;
      defaultValue =
          LiteralExpr.fromThrift(column.getDefault_value().getNodes().get(0),
              defaultValueType);
    }
    int blockSize = 0;
    if (column.isSetBlock_size()) blockSize = column.getBlock_size();
    String comment = (column.isSetComment() && !column.getComment().isEmpty()) ?
        column.getComment() : null;
    return new KuduColumn(column.getKudu_column_name(), columnType, column.isIs_key(),
        column.isIs_primary_key_unique(), isNullable, column.isIs_auto_incrementing(),
        encoding, compression, defaultValue, blockSize, comment, position);
  }

  // Create KuduColumn for auto-incrementing column in given 'position'.
  // This function is called when creating temporary KuduTable object for CTAS when
  // the primary key is not unique.
  public static KuduColumn createAutoIncrementingColumn(int position)
      throws ImpalaRuntimeException {
    org.apache.kudu.Type kuduType = Schema.getAutoIncrementingColumnType();
    Preconditions.checkArgument(kuduType != org.apache.kudu.Type.DECIMAL &&
        kuduType != org.apache.kudu.Type.VARCHAR);
    Type type = KuduUtil.toImpalaType(kuduType, null);
    return new KuduColumn(Schema.getAutoIncrementingColumnName(), type,
        /* isKey */true, /* isPrimaryKeyUnique */false, /* isNullable */false,
        /* isAutoIncrementing */true, /* encoding */null, /* compression */null,
        /* defaultValue */null, /* blockSize */0, /* comment */"", position);
  }

  public String getKuduName() { return kuduName_; }
  public boolean isKey() { return isKey_; }
  public boolean isPrimaryKeyUnique() { return isPrimaryKeyUnique_; }
  public boolean isNullable() { return isNullable_; }
  public boolean isAutoIncrementing() { return isAutoIncrementing_; }
  public Encoding getEncoding() { return encoding_; }
  public CompressionAlgorithm getCompression() { return compression_; }
  public int getBlockSize() { return blockSize_; }
  public boolean hasDefaultValue() { return defaultValue_ != null; }

  /**
   * Returns a SQL string representation of the default value. Similar to calling
   * LiteralExpr.toSql(), but this handles TIMESTAMPs specially because
   * TIMESTAMP default values are stored as BIGINTs representing unix time in
   * microseconds. For TIMESTAMP columns, the returned string is the function to
   * convert unix times in microseconds to TIMESTAMPs with the value as its parameter.
   */
  public String getDefaultValueSql() {
    if (!hasDefaultValue()) return null;
    if (!type_.isTimestamp()) return defaultValue_.toSql();
    return "unix_micros_to_utc_timestamp(" + defaultValue_.getStringValue() + ")";
  }

  /**
   * Returns a string representation of the default value. This calls getStringValue()
   * but is exposed so defaultValue_ can be encapsulated as it has special handling for
   * TIMESTAMP column types.
   */
  public String getDefaultValueString() {
    if (!hasDefaultValue()) return null;
    return defaultValue_.getStringValue();
  }

  @Override
  public TColumn toThrift() {
    TColumn colDesc = new TColumn(name_, type_.toThrift());
    KuduUtil.setColumnOptions(
        colDesc, isKey_, isPrimaryKeyUnique_, isNullable_, isAutoIncrementing_,
        encoding_, compression_, defaultValue_, blockSize_, kuduName_);
    if (comment_ != null) colDesc.setComment(comment_);
    colDesc.setCol_stats(getStats().toThrift());
    colDesc.setPosition(position_);
    colDesc.setIs_kudu_column(true);
    return colDesc;
  }
}
