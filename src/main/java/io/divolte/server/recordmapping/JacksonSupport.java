/*
 * Copyright 2015 GoDataDriven B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.divolte.server.recordmapping;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public class JacksonSupport {
    private JacksonSupport() {
        // Prevent external instantiation.
    }

    static final AvroGenericRecordMapper AVRO_MAPPER =
            new AvroGenericRecordMapper(
                    new ObjectMapper().reader()
                                      .with(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY,
                                            DeserializationFeature.UNWRAP_SINGLE_VALUE_ARRAYS)
                                      .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
}
