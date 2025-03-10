/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2023 QuestDB
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

package io.questdb.cairo.mig;

import io.questdb.cairo.CairoConfiguration;
import io.questdb.cairo.CairoEngine;
import io.questdb.cairo.CairoException;
import io.questdb.cairo.TableUtils;
import io.questdb.cairo.vm.Vm;
import io.questdb.cairo.vm.api.MemoryARW;
import io.questdb.cairo.vm.api.MemoryMARW;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.std.FilesFacade;
import io.questdb.std.IntObjHashMap;
import io.questdb.std.MemoryTag;
import io.questdb.std.Unsafe;
import io.questdb.std.str.Path;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;

import static io.questdb.cairo.TableUtils.META_OFFSET_VERSION;
import static io.questdb.cairo.TableUtils.openFileRWOrFail;

public class EngineMigration {

    private static final Log LOG = LogFactory.getLog(EngineMigration.class);
    private static final IntObjHashMap<MigrationAction> MIGRATIONS = new IntObjHashMap<>();

    public static void migrateEngineTo(CairoEngine engine, int latestVersion, boolean force) {
        final FilesFacade ff = engine.getConfiguration().getFilesFacade();
        final CairoConfiguration configuration = engine.getConfiguration();
        int tempMemSize = Long.BYTES;
        long mem = Unsafe.malloc(tempMemSize, MemoryTag.NATIVE_MIG);

        try (
                MemoryARW virtualMem = Vm.getARWInstance(ff.getPageSize(), Integer.MAX_VALUE, MemoryTag.NATIVE_MIG_MMAP);
                Path path = new Path();
                MemoryMARW rwMemory = Vm.getMARWInstance()
        ) {
            MigrationContext context = new MigrationContext(engine, mem, tempMemSize, virtualMem, rwMemory);
            path.of(configuration.getRoot());

            // check if all tables have been upgraded already
            path.concat(TableUtils.UPGRADE_FILE_NAME).$();
            final boolean existed = !force && ff.exists(path);
            int upgradeFd = openFileRWOrFail(ff, path, configuration.getWriterFileOpenOpts());
            LOG.debug()
                    .$("open [fd=").$(upgradeFd)
                    .$(", path=").$(path)
                    .I$();
            if (existed) {
                int currentVersion = TableUtils.readIntOrFail(
                        ff,
                        upgradeFd,
                        0,
                        mem,
                        path
                );

                if (currentVersion >= latestVersion) {
                    LOG.info().$("table structures are up to date").$();
                    ff.close(upgradeFd);
                    upgradeFd = -1;
                }
            }

            if (upgradeFd != -1) {
                try {
                    LOG.info().$("upgrading database [version=").$(latestVersion).I$();
                    if (upgradeTables(context, latestVersion)) {
                        TableUtils.writeIntOrFail(
                                ff,
                                upgradeFd,
                                0,
                                latestVersion,
                                mem,
                                path
                        );
                    }
                } finally {
                    Vm.bestEffortClose(
                            ff,
                            LOG,
                            upgradeFd,
                            Integer.BYTES
                    );
                }
            }
        } finally {
            Unsafe.free(mem, tempMemSize, MemoryTag.NATIVE_MIG);
        }
    }

    private static @Nullable MigrationAction getMigrationToVersion(int version) {
        return MIGRATIONS.get(version);
    }

    private static boolean upgradeTables(MigrationContext context, int latestVersion) {
        final FilesFacade ff = context.getFf();
        final CharSequence root = context.getConfiguration().getRoot();
        long mem = context.getTempMemory(8);
        final AtomicBoolean updateSuccess = new AtomicBoolean(true);

        try (Path path = new Path(); Path copyPath = new Path()) {
            path.of(root);
            copyPath.of(root);
            final int rootLen = path.length();

            ff.iterateDir(path.$(), (pUtf8NameZ, type) -> {
                if (ff.isDirOrSoftLinkDirNoDots(path, rootLen, pUtf8NameZ, type)) {
                    copyPath.trimTo(rootLen);
                    copyPath.concat(pUtf8NameZ);
                    final int tablePlen = path.length();

                    if (ff.exists(path.concat(TableUtils.META_FILE_NAME).$())) {
                        final int fdMeta = openFileRWOrFail(ff, path, context.getConfiguration().getWriterFileOpenOpts());
                        try {
                            int currentTableVersion = TableUtils.readIntOrFail(ff, fdMeta, META_OFFSET_VERSION, mem, path);
                            if (currentTableVersion < latestVersion) {
                                LOG.info()
                                        .$("upgrading [path=").utf8(copyPath.$())
                                        .$(", fromVersion=").$(currentTableVersion)
                                        .$(", toVersion=").$(latestVersion)
                                        .I$();

                                copyPath.trimTo(tablePlen);
                                backupFile(ff, path, copyPath, TableUtils.META_FILE_NAME, currentTableVersion);

                                path.trimTo(tablePlen);
                                context.of(path, copyPath, fdMeta);

                                for (int ver = currentTableVersion + 1; ver <= latestVersion; ver++) {
                                    final MigrationAction migration = getMigrationToVersion(ver);
                                    if (migration != null) {
                                        try {
                                            LOG.info().$("upgrading table [path=").utf8(path)
                                                    .$(", toVersion=").$(ver)
                                                    .I$();
                                            migration.migrate(context);
                                            path.trimTo(tablePlen);
                                            copyPath.trimTo(tablePlen);
                                        } catch (Throwable e) {
                                            LOG.error().$("failed to upgrade table [path=").utf8(path.trimTo(tablePlen))
                                                    .$(", e=").$(e)
                                                    .I$();
                                            throw e;
                                        }
                                    }

                                    path.trimTo(tablePlen).concat(TableUtils.META_FILE_NAME).$();
                                    LOG.info().$("upgrading table _meta [path=").utf8(path).$(", toVersion=").$(ver).I$();
                                    TableUtils.writeIntOrFail(ff, fdMeta, META_OFFSET_VERSION, ver, mem, path);
                                    path.trimTo(tablePlen);
                                }
                            }
                        } finally {
                            ff.close(fdMeta);
                            path.trimTo(tablePlen);
                            copyPath.trimTo(tablePlen);
                        }
                    }
                }
            });
            LOG.info().$("upgraded tables to ").$(latestVersion).$();
        }
        return updateSuccess.get();
    }

    static void backupFile(FilesFacade ff, Path src, Path toTemp, String backupName, int version) {
        // make a copy
        int copyPathLen = toTemp.length();
        try {
            toTemp.concat(backupName).put(".v").put(version);
            int versionLen = toTemp.length();
            for (int i = 1; ff.exists(toTemp.$()); i++) {
                // if backup file already exists
                // add .<num> at the end until file name is unique
                LOG.info().$("backup dest exists [to=").$(toTemp).I$();
                toTemp.trimTo(versionLen).put('.').put(i);
            }

            LOG.info().$("backing up [file=").utf8(src).$(", to=").utf8(toTemp).I$();
            if (ff.copy(src.$(), toTemp) < 0) {
                throw CairoException.critical(ff.errno()).put("Cannot backup transaction file [to=").put(toTemp).put(']');
            }
        } finally {
            toTemp.trimTo(copyPathLen);
        }
    }

    static {
        MIGRATIONS.put(417, Mig505::migrate);
        // there is no tagged version with _meta 418, this is something unreleased
        MIGRATIONS.put(418, Mig506::migrate);
        MIGRATIONS.put(419, Mig600::migrate);
        MIGRATIONS.put(420, Mig605::migrate);
        MIGRATIONS.put(422, Mig607::migrate);
        MIGRATIONS.put(423, Mig608::migrate);
        MIGRATIONS.put(424, Mig609::migrate);
        MIGRATIONS.put(425, Mig614::migrate);
        MIGRATIONS.put(426, Mig620::migrate);
    }
}
