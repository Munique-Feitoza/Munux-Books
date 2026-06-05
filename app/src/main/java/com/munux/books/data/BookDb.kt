package com.munux.books.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

enum class BookFormat { EPUB, PDF, UNKNOWN }
enum class TranslationStatus { NONE, IN_PROGRESS, DONE, FAILED }

@Entity(tableName = "books")
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String?,
    val filePath: String,
    val format: BookFormat,
    val totalPages: Int = 0,
    val currentPage: Int = 0,
    val addedAt: Long = System.currentTimeMillis(),
    val lastOpenedAt: Long = System.currentTimeMillis(),
    val translationStatus: TranslationStatus = TranslationStatus.NONE,
    val translatedChapters: Int = 0,
    val translationLang: String? = null,
    val translationError: String? = null,
    /** Epoch ms estimado em que o limite do Gemini libera de novo (null = sem bloqueio). */
    val retryAfterAt: Long? = null,
    /**
     * Razão (0–1) de quanto do capítulo atual já foi lido. Sobrevive a mudança
     * de fonte/tema porque é relativo ao tamanho total do conteúdo, não a um
     * número absoluto de pixels.
     */
    val currentScrollRatio: Float = 0f
)

@Entity(
    tableName = "translations",
    primaryKeys = ["bookId", "chapterIndex", "targetLang"]
)
data class TranslationEntry(
    val bookId: Long,
    val chapterIndex: Int,
    val targetLang: String,
    val sourceHash: String,
    val translatedContent: String,
    val translatedAt: Long = System.currentTimeMillis()
)

class Converters {
    @TypeConverter
    fun fromFormat(v: BookFormat): String = v.name

    @TypeConverter
    fun toFormat(v: String): BookFormat =
        runCatching { BookFormat.valueOf(v) }.getOrDefault(BookFormat.UNKNOWN)

    @TypeConverter
    fun fromTranslationStatus(v: TranslationStatus): String = v.name

    @TypeConverter
    fun toTranslationStatus(v: String): TranslationStatus =
        runCatching { TranslationStatus.valueOf(v) }.getOrDefault(TranslationStatus.NONE)
}

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY lastOpenedAt DESC")
    fun observeAll(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getById(id: Long): Book?

    @Query("SELECT * FROM books WHERE id = :id")
    fun observeById(id: Long): Flow<Book?>

    @Insert
    suspend fun insert(book: Book): Long

    @Update
    suspend fun update(book: Book)

    @Delete
    suspend fun delete(book: Book)

    @Query("UPDATE books SET currentPage = :page, lastOpenedAt = :now WHERE id = :id")
    suspend fun updateProgress(id: Long, page: Int, now: Long = System.currentTimeMillis())

    @Query("UPDATE books SET totalPages = :total WHERE id = :id")
    suspend fun updateTotalPages(id: Long, total: Int)

    @Query("""
        UPDATE books
        SET translationStatus = :status,
            translatedChapters = :chapters,
            translationLang = :lang,
            translationError = :error,
            retryAfterAt = NULL
        WHERE id = :id
    """)
    suspend fun updateTranslationStatus(
        id: Long,
        status: TranslationStatus,
        chapters: Int,
        lang: String?,
        error: String?
    )

    @Query("UPDATE books SET retryAfterAt = :at WHERE id = :id")
    suspend fun updateRetryAfter(id: Long, at: Long?)

    @Query("""
        UPDATE books
        SET currentPage = :chapter, currentScrollRatio = :ratio, lastOpenedAt = :now
        WHERE id = :id
    """)
    suspend fun updatePosition(
        id: Long,
        chapter: Int,
        ratio: Float,
        now: Long = System.currentTimeMillis()
    )
}

@Dao
interface TranslationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: TranslationEntry)

    @Query("""
        SELECT * FROM translations
        WHERE bookId = :bookId AND targetLang = :lang
        ORDER BY chapterIndex ASC
    """)
    suspend fun getAllForBook(bookId: Long, lang: String): List<TranslationEntry>

    @Query("""
        SELECT * FROM translations
        WHERE bookId = :bookId AND chapterIndex = :index AND targetLang = :lang
        LIMIT 1
    """)
    suspend fun get(bookId: Long, index: Int, lang: String): TranslationEntry?

    @Query("""
        SELECT COUNT(*) FROM translations
        WHERE bookId = :bookId AND targetLang = :lang
    """)
    suspend fun countForBook(bookId: Long, lang: String): Int

    @Query("DELETE FROM translations WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: Long)
}

@Database(
    entities = [Book::class, TranslationEntry::class],
    version = 4,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun translationDao(): TranslationDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN retryAfterAt INTEGER")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN currentScrollRatio REAL NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "munux-books.db"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
