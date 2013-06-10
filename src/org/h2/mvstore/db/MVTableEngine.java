/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore.db;

import java.util.ArrayList;
import java.util.List;

import org.h2.api.TableEngine;
import org.h2.command.ddl.CreateTableData;
import org.h2.engine.Constants;
import org.h2.engine.Database;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.db.TransactionStore.Transaction;
import org.h2.table.RegularTable;
import org.h2.table.TableBase;
import org.h2.util.New;

/**
 * A table engine that internally uses the MVStore.
 */
public class MVTableEngine implements TableEngine {
	//Database、Store、TransactionStore、MVStore的实例个数是一对一的
    @Override
    public TableBase createTable(CreateTableData data) {
        Database db = data.session.getDatabase();
        if (!data.persistData || (data.temporary && !data.persistIndexes)) {
            return new RegularTable(data);
        }
        Store store = db.getMvStore();
        if (store == null) {
            byte[] key = db.getFilePasswordHash();
            String dbPath = db.getDatabasePath();
            //这个Builder只设置:fileName、readOnly、encrypt三个参数，其他的都使用默认值
            MVStore.Builder builder = new MVStore.Builder();
            if (dbPath == null) { //内存数据库
                store = new Store(db, builder.open());
            } else {
                builder.fileName(dbPath + Constants.SUFFIX_MV_FILE); //如"E:/H2/baseDir/mydb.mv.db"
                if (db.isReadOnly()) {
                    builder.readOnly();
                }
                if (key != null) {
                    char[] password = new char[key.length];
                    for (int i = 0; i < key.length; i++) {
                        password[i] = (char) key[i];
                    }
                    builder.encryptionKey(password);
                }
                store = new Store(db, builder.open());
            }
            db.setMvStore(store);
        }
        MVTable table = new MVTable(data, store);
        store.openTables.add(table);
        table.init(data.session);
        return table;
    }

    /**
     * A store with open tables.
     */
    public static class Store { //Database、Store、TransactionStore、MVStore的实例个数是一对一

        /**
         * The database.
         */
        final Database db;

        /**
         * The list of open tables.
         */
        final ArrayList<MVTable> openTables = New.arrayList();

        /**
         * The store.
         */
        private final MVStore store;

        /**
         * The transaction store.
         */
        private final TransactionStore transactionStore;

        public Store(Database db, MVStore store) {
            this.db = db;
            this.store = store;
            this.transactionStore = new TransactionStore(store,
                    new ValueDataType(null, db, null));
        }

        public MVStore getStore() {
            return store;
        }

        public TransactionStore getTransactionStore() {
            return transactionStore;
        }

        public List<MVTable> getTables() {
            return openTables;
        }

        /**
         * Remove a table.
         *
         * @param table the table
         */
        public void removeTable(MVTable table) {
            openTables.remove(table);
        }

        /**
         * Store all pending changes.
         */
        public void store() { //在BackupCommand中用到，执行CHECKPOINT时也用到
            if (!store.isReadOnly()) {
                store.commit();
                store.compact(50);
                store.store();
            }
        }

        /**
         * Close the store, without persisting changes.
         */
        public void closeImmediately() { //不正常关闭数据库时调用
            if (store.isClosed()) {
                return;
            }
            store.closeImmediately();
        }

        /**
         * Close the store. Pending changes are persisted.
         */
        public void close() { //正常关闭数据库时调用
            if (!store.isClosed()) {
                if (!store.isReadOnly()) {
                    store.store();
                }
                store.close();
            }
        }

        public void setWriteDelay(int value) {
            store.setWriteDelay(value);
        }

        public void rollback() {
            List<Transaction> list = transactionStore.getOpenTransactions();
            for (Transaction t : list) {
                t.rollback();
            }
        }

    }

}
