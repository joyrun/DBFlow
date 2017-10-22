package com.raizlabs.android.dbflow.config

import android.content.Context
import com.raizlabs.android.dbflow.annotation.Database
import com.raizlabs.android.dbflow.annotation.QueryModel
import com.raizlabs.android.dbflow.annotation.Table
import com.raizlabs.android.dbflow.runtime.BaseTransactionManager
import com.raizlabs.android.dbflow.runtime.ContentResolverNotifier
import com.raizlabs.android.dbflow.runtime.ModelNotifier
import com.raizlabs.android.dbflow.sql.migration.Migration
import com.raizlabs.android.dbflow.structure.BaseModelView
import com.raizlabs.android.dbflow.structure.ModelAdapter
import com.raizlabs.android.dbflow.structure.ModelViewAdapter
import com.raizlabs.android.dbflow.structure.QueryModelAdapter
import com.raizlabs.android.dbflow.structure.database.DatabaseHelperListener
import com.raizlabs.android.dbflow.structure.database.DatabaseWrapper
import com.raizlabs.android.dbflow.structure.database.FlowSQLiteOpenHelper
import com.raizlabs.android.dbflow.structure.database.OpenHelper
import com.raizlabs.android.dbflow.structure.database.transaction.DefaultTransactionManager
import com.raizlabs.android.dbflow.structure.database.transaction.DefaultTransactionQueue
import com.raizlabs.android.dbflow.structure.database.transaction.ITransaction
import com.raizlabs.android.dbflow.structure.database.transaction.Transaction
import java.util.*

/**
 * Description: The main interface that all Database implementations extend from. This is for internal usage only
 * as it will be generated for every [Database].
 */
abstract class DatabaseDefinition {

    private val migrationMap = hashMapOf<Int, MutableList<Migration>>()

    private val modelAdapters = hashMapOf<Class<*>, ModelAdapter<*>>()

    private val modelTableNames = hashMapOf<String, Class<*>>()

    private val modelViewAdapterMap = linkedMapOf<Class<*>, ModelViewAdapter<*>>()

    private val queryModelAdapterMap = linkedMapOf<Class<*>, QueryModelAdapter<*>>()

    /**
     * The helper that manages database changes and initialization
     */
    private var openHelper: OpenHelper? = null

    /**
     * Allows for the app to listen for database changes.
     */
    private var helperListener: DatabaseHelperListener? = null

    /**
     * Used when resetting the DB
     */
    private var isResetting = false

    lateinit var transactionManager: BaseTransactionManager
        private set

    private var databaseConfig: DatabaseConfig? = null

    private var modelNotifier: ModelNotifier? = null

    /**
     * @return a list of all model classes in this database.
     */
    val modelClasses: List<Class<*>>
        get() = ArrayList(modelAdapters.keys)

    /**
     * @return the [BaseModelView] list for this database.
     */
    val modelViews: List<Class<*>>
        get() = ArrayList(modelViewAdapterMap.keys)

    /**
     * @return The list of [ModelViewAdapter]. Internal method for
     * creating model views in the DB.
     */
    val modelViewAdapters: List<ModelViewAdapter<*>>
        get() = ArrayList<ModelViewAdapter>(modelViewAdapterMap.values)

    /**
     * @return The list of [QueryModelAdapter]. Internal method for creating query models in the DB.
     */
    val modelQueryAdapters: List<QueryModelAdapter<*>>
        get() = ArrayList<QueryModelAdapter>(queryModelAdapterMap.values)

    /**
     * @return The map of migrations to DB version
     */
    val migrations: Map<Int, List<Migration>>
        get() = migrationMap

    val helper: OpenHelper
        @Synchronized get() {
            if (openHelper == null) {
                val config = FlowManager.getConfig().getDatabaseConfigMap()[associatedDatabaseClassFile]
                openHelper = if (config?.openHelperCreator != null) {
                    config.openHelperCreator.invoke(this, helperListener)
                } else {
                    FlowSQLiteOpenHelper(this, helperListener)
                }
                openHelper?.performRestoreFromBackup()
            }
            return openHelper!!
        }

    val writableDatabase: DatabaseWrapper
        get() = helper.database

    /**
     * @return The name of this database as defined in [Database]
     */
    val databaseName: String
        get() = databaseConfig?.databaseName ?: associatedDatabaseClassFile.simpleName

    /**
     * @return The file name that this database points to
     */
    val databaseFileName: String
        get() = databaseName + databaseExtensionName

    /**
     * @return the extension for the file name.
     */
    val databaseExtensionName: String
        get() = databaseConfig?.databaseExtensionName ?: ".db"

    /**
     * @return True if the database will reside in memory.
     */
    val isInMemory: Boolean
        get() = databaseConfig?.isInMemory ?: false

    /**
     * @return The version of the database currently.
     */
    abstract val databaseVersion: Int

    /**
     * @return True if the [Database.foreignKeyConstraintsEnforced] annotation is true.
     */
    abstract val isForeignKeysSupported: Boolean

    /**
     * @return The class that defines the [Database] annotation.
     */
    abstract val associatedDatabaseClassFile: Class<*>

    /**
     * @return True if the database is ok. If backups are enabled, we restore from backup and will
     * override the return value if it replaces the main DB.
     */
    val isDatabaseIntegrityOk: Boolean
        get() = helper.isDatabaseIntegrityOk

    init {
        applyDatabaseConfig(FlowManager.getConfig().getDatabaseConfigMap()[associatedDatabaseClassFile])
    }

    /**
     * Applies a database configuration object to this class.
     */
    internal fun applyDatabaseConfig(databaseConfig: DatabaseConfig?) {
        this.databaseConfig = databaseConfig
        if (databaseConfig != null) {
            // initialize configuration if exists.
            val tableConfigCollection = databaseConfig.tableConfigMap.values
            for (tableConfig in tableConfigCollection) {
                val modelAdapter = modelAdapters[tableConfig.tableClass()] ?: continue
                if (tableConfig.listModelLoader() != null) {
                    modelAdapter.setListModelLoader(tableConfig.listModelLoader())
                }

                if (tableConfig.singleModelLoader() != null) {
                    modelAdapter.setSingleModelLoader(tableConfig.singleModelLoader())
                }

                if (tableConfig.modelSaver() != null) {
                    modelAdapter.setModelSaver(tableConfig.modelSaver())
                }

            }
            helperListener = databaseConfig.helperListener
        }
        transactionManager = if (databaseConfig?.transactionManagerCreator == null) {
            DefaultTransactionManager(this)
        } else {
            databaseConfig.transactionManagerCreator.invoke(this)
        }
    }

    protected fun <T> addModelAdapter(modelAdapter: ModelAdapter<T>, holder: DatabaseHolder) {
        holder.putDatabaseForTable(modelAdapter.modelClass, this)
        modelTableNames.put(modelAdapter.tableName, modelAdapter.modelClass)
        modelAdapters.put(modelAdapter.modelClass, modelAdapter)
    }

    protected fun <T> addModelViewAdapter(modelViewAdapter: ModelViewAdapter<T>, holder: DatabaseHolder) {
        holder.putDatabaseForTable(modelViewAdapter.modelClass, this)
        modelViewAdapterMap.put(modelViewAdapter.modelClass, modelViewAdapter)
    }

    protected fun <T> addQueryModelAdapter(queryModelAdapter: QueryModelAdapter<T>, holder: DatabaseHolder) {
        holder.putDatabaseForTable(queryModelAdapter.modelClass, this)
        queryModelAdapterMap.put(queryModelAdapter.modelClass, queryModelAdapter)
    }

    protected fun addMigration(version: Int, migration: Migration) {
        var list: MutableList<Migration>? = migrationMap[version]
        if (list == null) {
            list = arrayListOf()
            migrationMap.put(version, list)
        }
        list.add(migration)
    }

    /**
     * Internal method used to create the database schema.
     *
     * @return List of Model Adapters
     */
    fun getModelAdapters(): List<ModelAdapter<*>> = modelAdapters.values.toList()

    /**
     * Returns the associated [ModelAdapter] within this database for
     * the specified table. If the Model is missing the [Table] annotation,
     * this will return null.
     *
     * @param table The model that exists in this database.
     * @return The ModelAdapter for the table.
     */
    fun <T> getModelAdapterForTable(table: Class<T>): ModelAdapter<T>? {
        @Suppress("UNCHECKED_CAST")
        return modelAdapters[table] as ModelAdapter<T>?
    }

    /**
     * @param tableName The name of the table in this db.
     * @return The associated [ModelAdapter] within this database for the specified table name.
     * If the Model is missing the [Table] annotation, this will return null.
     */
    fun getModelClassForName(tableName: String): Class<*>? {
        return modelTableNames[tableName]
    }

    /**
     * @param table the VIEW class to retrieve the ModelViewAdapter from.
     * @return the associated [ModelViewAdapter] for the specified table.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> getModelViewAdapterForTable(table: Class<T>): ModelViewAdapter<T>? {
        return modelViewAdapterMap[table] as ModelViewAdapter<T>?
    }

    /**
     * @param queryModel The [QueryModel] class
     * @return The adapter that corresponds to the specified class.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> getQueryModelAdapterForQueryClass(queryModel: Class<T>): QueryModelAdapter<T>? {
        return queryModelAdapterMap[queryModel] as QueryModelAdapter<T>?
    }

    fun getModelNotifier(): ModelNotifier {
        if (modelNotifier == null) {
            val config = FlowManager.getConfig().getDatabaseConfigMap()[associatedDatabaseClassFile]
            modelNotifier = if (config?.modelNotifier == null) {
                ContentResolverNotifier()
            } else {
                config.modelNotifier
            }
        }
        return modelNotifier!!
    }

    fun beginTransactionAsync(transaction: ITransaction): Transaction.Builder {
        return Transaction.Builder(transaction, this)
    }

    fun executeTransaction(transaction: ITransaction) {
        val database = writableDatabase
        try {
            database.beginTransaction()
            transaction.execute(database)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    /**
     * @return True if the [Database.consistencyCheckEnabled] annotation is true.
     */
    abstract fun areConsistencyChecksEnabled(): Boolean

    /**
     * @return True if the [Database.backupEnabled] annotation is true.
     */
    abstract fun backupEnabled(): Boolean


    @Deprecated("use {@link #reset()}")
    fun reset(context: Context) {
        reset(databaseConfig)
    }

    /**
     * Performs a full deletion of this database. Reopens the [FlowSQLiteOpenHelper] as well.
     *
     * Reapplies the [DatabaseConfig] if we have one.
     * @param databaseConfig sets a new [DatabaseConfig] on this class.
     */
    @JvmOverloads
    fun reset(databaseConfig: DatabaseConfig? = this.databaseConfig) {
        if (!isResetting) {
            destroy()
            // reapply configuration before opening it.
            applyDatabaseConfig(databaseConfig)
            helper.database
        }
    }

    /**
     * Reopens the DB with the new [DatabaseConfig] specified.
     * Reapplies the [DatabaseConfig] if we have one.
     *
     * @param databaseConfig sets a new [DatabaseConfig] on this class.
     */
    @JvmOverloads
    fun reopen(databaseConfig: DatabaseConfig? = this.databaseConfig) {
        if (!isResetting) {
            close()
            openHelper = null
            applyDatabaseConfig(databaseConfig)
            helper.database
            isResetting = false
        }
    }

    /**
     * Deletes the underlying database and destroys it.
     */
    fun destroy() {
        if (!isResetting) {
            isResetting = true
            close()
            FlowManager.context.deleteDatabase(databaseFileName)
            openHelper = null
            isResetting = false
        }
    }

    /**
     * Closes the DB and stops the [BaseTransactionManager]
     */
    fun close() {
        transactionManager.stopQueue()
        modelAdapters.values.forEach {
            with(it) {
                closeInsertStatement()
                closeCompiledStatement()
                closeDeleteStatement()
                closeUpdateStatement()
            }
        }
        helper.closeDB()
    }

    /**
     * Saves the database as a backup on the [DefaultTransactionQueue]. This will
     * create a THIRD database to use as a backup to the backup in case somehow the overwrite fails.
     *
     * @throws java.lang.IllegalStateException if [Database.backupEnabled]
     * or [Database.consistencyCheckEnabled] is not enabled.
     */
    fun backupDatabase() {
        helper.backupDB()
    }

}