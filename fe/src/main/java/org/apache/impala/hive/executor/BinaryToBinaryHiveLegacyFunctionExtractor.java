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

package org.apache.impala.hive.executor;

import org.apache.hadoop.io.BytesWritable;
import org.apache.impala.catalog.CatalogException;
import org.apache.impala.catalog.PrimitiveType;
import org.apache.impala.catalog.ScalarType;
import org.apache.impala.common.ImpalaException;

public class BinaryToBinaryHiveLegacyFunctionExtractor
    extends HiveLegacyFunctionExtractor {
  @Override
  protected ScalarType resolveType(
      Class<?> type, java.util.function.Function<JavaUdfDataType, String> errorHandler)
      throws ImpalaException {
    if (type == BytesWritable.class) {
      return ScalarType.createType(PrimitiveType.BINARY);
    } else {
      return super.resolveType(type, errorHandler);
    }
  }
}
