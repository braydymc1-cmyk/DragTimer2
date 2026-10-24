package com.performance.gpstimer

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

@Entity(tableName = "performance_runs")
data class RunRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampEpochMs: Long,
    val testMode: String,
    val primaryTimeMs: Long,
    val trapSpeedKmh: Double,
    val totalDistanceM: Double,
    val time60ftMs: Long? = null,
    val time330ftMs: Long? = null,
    val timeEighthMileMs: Long? = null,
    val speedEighthMileKmh: Double? = null,
    val time1000ftMs: Long? = null,
    val time0to100Ms: Long? = null,
    val time100to150Ms: Long? = null,
    val time150to200Ms: Long? = null,
    val slopePercent: Double,
    val isValidSlope: Boolean,
    val peakG: Double,
    val rolloutEnabled: Boolean,
    val gpsHz: Double,
    @ColumnInfo(defaultValue = "NULL") val reactionTimeMs: Long? = null,
    @ColumnInfo(defaultValue = "0") val redLightJump: Boolean = false,
    @ColumnInfo(defaultValue = "'NONE'") val treeType: String = "NONE",
    @ColumnInfo(defaultValue = "NULL") val timeHalfMileMs: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val speedHalfMileKmh: Double? = null,
    @ColumnInfo(defaultValue = "NULL") val timeOneMileMs: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val speedOneMileKmh: Double? = null,
    @ColumnInfo(defaultValue = "NULL") val brakingStartSpeedKmh: Double? = null,
    @ColumnInfo(defaultValue = "NULL") val brakingDistanceM: Double? = null,
    @ColumnInfo(defaultValue = "NULL") val avgBrakingG: Double? = null,
    @ColumnInfo(defaultValue = "1") val vehicleProfileId: Long = 1L,
    @ColumnInfo(defaultValue = "'Default Vehicle'") val vehicleName: String = "Default Vehicle",
    @ColumnInfo(defaultValue = "NULL") val ambientTempC: Double? = null,
    @ColumnInfo(defaultValue = "NULL") val barometricPressureHpa: Double? = null,
    @ColumnInfo(defaultValue = "NULL") val humidityPct: Double? = null,
    @ColumnInfo(defaultValue = "NULL") val densityAltitudeFt: Int? = null,
    @ColumnInfo(defaultValue = "'[]'") val telemetryJson: String = "[]"
)

@Entity(tableName = "vehicle_profiles")
data class VehicleProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val makeModel: String,
    val weightKg: Double,
    val drivetrain: String,
    val notes: String = "",
    @ColumnInfo(defaultValue = "''") val photoUri: String = ""
)

@Entity(tableName = "track_layouts")
data class TrackLayout(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startLat: Double,
    val startLon: Double,
    val sector1Lat: Double = 0.0,
    val sector1Lon: Double = 0.0,
    val sector2Lat: Double = 0.0,
    val sector2Lon: Double = 0.0,
    val finishLat: Double = 0.0,
    val finishLon: Double = 0.0,
    val isCircuit: Boolean = true,
    val bestLapMs: Long? = null
)

@Entity(tableName = "track_laps")
data class TrackLapRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val trackName: String,
    val vehicleName: String,
    val timestampEpochMs: Long,
    val lapNumber: Int,
    val lapTimeMs: Long,
    val sector1Ms: Long? = null,
    val sector2Ms: Long? = null,
    val sector3Ms: Long? = null,
    val maxSpeedKmh: Double,
    val peakLatG: Double,
    val peakBrakingG: Double,
    val telemetryJson: String = "[]"
)

@Dao
interface RunDao {
    @Query("SELECT * FROM performance_runs ORDER BY timestampEpochMs DESC")
    fun getAllRuns(): Flow<List<RunRecord>>

    @Query("SELECT * FROM performance_runs ORDER BY timestampEpochMs DESC")
    suspend fun getAllRunsSnapshot(): List<RunRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRun(run: RunRecord): Long

    @Delete
    suspend fun deleteRun(run: RunRecord)

    @Query("SELECT * FROM vehicle_profiles ORDER BY id ASC")
    fun getAllVehicles(): Flow<List<VehicleProfile>>

    @Query("SELECT * FROM vehicle_profiles ORDER BY id ASC")
    suspend fun getAllVehiclesSnapshot(): List<VehicleProfile>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVehicle(vehicle: VehicleProfile): Long

    @Delete
    suspend fun deleteVehicle(vehicle: VehicleProfile)

    @Query("SELECT * FROM track_layouts ORDER BY id ASC")
    fun getAllTracks(): Flow<List<TrackLayout>>

    @Query("SELECT * FROM track_layouts ORDER BY id ASC")
    suspend fun getAllTracksSnapshot(): List<TrackLayout>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrack(track: TrackLayout): Long

    @Delete
    suspend fun deleteTrack(track: TrackLayout)

    @Query("SELECT * FROM track_laps ORDER BY timestampEpochMs DESC")
    fun getAllLaps(): Flow<List<TrackLapRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLap(lap: TrackLapRecord): Long
}

@Database(
    entities = [RunRecord::class, VehicleProfile::class, TrackLayout::class, TrackLapRecord::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun runDao(): RunDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE performance_runs ADD COLUMN reactionTimeMs INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE performance_runs ADD COLUMN redLightJump INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE performance_runs ADD COLUMN treeType TEXT NOT NULL DEFAULT 'NONE'")
                db.execSQL("ALTER TABLE performance_runs ADD COLUMN timeHalfMileMs INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE performance_runs ADD COLUMN speedHalfMileKmh REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE performance_runs ADD COLUMN timeOneMileMs INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE performance_runs ADD COLUMN speedOneMileKmh REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE performance_runs ADD COLUMN brakingStartSpeedKmh REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE performance_runs ADD COLUMN brakingDistanceM REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE performance_runs ADD COLUMN avgBrakingG REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE performance_runs ADD COLUMN vehicleProfileId INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE performance_runs ADD COLUMN vehicleName TEXT NOT NULL DEFAULT 'Default Vehicle'")
                db.execSQL("ALTER TABLE performance_runs ADD COLUMN ambientTempC REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE performance_runs ADD COLUMN barometricPressureHpa REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE performance_runs ADD COLUMN humidityPct REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE performance_runs ADD COLUMN densityAltitudeFt INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE performance_runs ADD COLUMN telemetryJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("CREATE TABLE IF NOT EXISTS `vehicle_profiles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `makeModel` TEXT NOT NULL, `weightKg` REAL NOT NULL, `drivetrain` TEXT NOT NULL, `notes` TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `track_layouts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `startLat` REAL NOT NULL, `startLon` REAL NOT NULL, `sector1Lat` REAL NOT NULL, `sector1Lon` REAL NOT NULL, `sector2Lat` REAL NOT NULL, `sector2Lon` REAL NOT NULL, `finishLat` REAL NOT NULL, `finishLon` REAL NOT NULL, `isCircuit` INTEGER NOT NULL, `bestLapMs` INTEGER)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `track_laps` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `trackId` INTEGER NOT NULL, `trackName` TEXT NOT NULL, `vehicleName` TEXT NOT NULL, `timestampEpochMs` INTEGER NOT NULL, `lapNumber` INTEGER NOT NULL, `lapTimeMs` INTEGER NOT NULL, `sector1Ms` INTEGER, `sector2Ms` INTEGER, `sector3Ms` INTEGER, `maxSpeedKmh` REAL NOT NULL, `peakLatG` REAL NOT NULL, `peakBrakingG` REAL NOT NULL, `telemetryJson` TEXT NOT NULL)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE vehicle_profiles ADD COLUMN photoUri TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "gps_drag_timer.db")
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .addCallback(object : Callback() {
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            CoroutineScope(Dispatchers.IO).launch {
                                val dao = getInstance(context).runDao()
                                if (dao.getAllTracksSnapshot().isEmpty()) {
                                    dao.insertTrack(
                                        TrackLayout(
                                            name = "Wanneroo Raceway (Barbagallo)",
                                            startLat = -31.67432,
                                            startLon = 115.78685,
                                            sector1Lat = -31.67108,
                                            sector1Lon = 115.78852,
                                            sector2Lat = -31.67610,
                                            sector2Lon = 115.79160,
                                            finishLat = -31.67432,
                                            finishLon = 115.78685,
                                            isCircuit = true
                                        )
                                    )
                                }
                            }
                        }
                    })
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
