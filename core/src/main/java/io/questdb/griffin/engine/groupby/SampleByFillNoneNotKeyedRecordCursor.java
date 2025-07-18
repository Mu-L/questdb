/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2024 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.griffin.engine.groupby;

import io.questdb.cairo.CairoConfiguration;
import io.questdb.cairo.sql.Function;
import io.questdb.griffin.engine.functions.GroupByFunction;
import io.questdb.std.ObjList;

class SampleByFillNoneNotKeyedRecordCursor extends AbstractVirtualRecordSampleByCursor {
    private final SimpleMapValue simpleMapValue;

    public SampleByFillNoneNotKeyedRecordCursor(
            CairoConfiguration configuration,
            SimpleMapValue simpleMapValue,
            ObjList<GroupByFunction> groupByFunctions,
            GroupByFunctionsUpdater groupByFunctionsUpdater,
            ObjList<Function> recordFunctions,
            int timestampIndex, // index of timestamp column in base cursor
            TimestampSampler timestampSampler,
            Function timezoneNameFunc,
            int timezoneNameFuncPos,
            Function offsetFunc,
            int offsetFuncPos,
            Function sampleFromFunc,
            int sampleFromFuncPos,
            Function sampleToFunc,
            int sampleToFuncPos
    ) {
        super(
                configuration,
                recordFunctions,
                timestampIndex,
                timestampSampler,
                groupByFunctions,
                groupByFunctionsUpdater,
                timezoneNameFunc,
                timezoneNameFuncPos,
                offsetFunc,
                offsetFuncPos,
                sampleFromFunc,
                sampleFromFuncPos,
                sampleToFunc,
                sampleToFuncPos
        );
        this.simpleMapValue = simpleMapValue;
        record.of(simpleMapValue);
    }

    @Override
    public long preComputedStateSize() {
        return super.preComputedStateSize();
    }

    @Override
    public boolean hasNext() {
        initTimestamps();
        return baseRecord != null && notKeyedLoop(simpleMapValue);
    }
}
