package mil.nga.geopackage.test.tiles.user;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import junit.framework.TestCase;
import mil.nga.geopackage.BoundingBox;
import mil.nga.geopackage.GeoPackage;
import mil.nga.geopackage.GeoPackageException;
import mil.nga.geopackage.db.SQLUtils;
import mil.nga.geopackage.db.SQLiteQueryBuilder;
import mil.nga.geopackage.projection.ProjectionConstants;
import mil.nga.geopackage.projection.ProjectionFactory;
import mil.nga.geopackage.test.TestUtils;
import mil.nga.geopackage.test.geom.GeoPackageGeometryDataUtils;
import mil.nga.geopackage.tiles.TileBoundingBoxUtils;
import mil.nga.geopackage.tiles.TileGrid;
import mil.nga.geopackage.tiles.matrix.TileMatrix;
import mil.nga.geopackage.tiles.matrix.TileMatrixDao;
import mil.nga.geopackage.tiles.matrix.TileMatrixKey;
import mil.nga.geopackage.tiles.matrixset.TileMatrixSet;
import mil.nga.geopackage.tiles.matrixset.TileMatrixSetDao;
import mil.nga.geopackage.tiles.user.TileColumn;
import mil.nga.geopackage.tiles.user.TileDao;
import mil.nga.geopackage.tiles.user.TileResultSet;
import mil.nga.geopackage.tiles.user.TileRow;
import mil.nga.geopackage.tiles.user.TileTable;
import mil.nga.geopackage.user.ColumnValue;
import mil.nga.geopackage.user.UserCoreResultUtils;

import com.j256.ormlite.stmt.PreparedQuery;
import com.j256.ormlite.stmt.QueryBuilder;

/**
 * Tiles Utility test methods
 * 
 * @author osbornb
 */
public class TileUtils {

	/**
	 * Test read
	 * 
	 * @param geoPackage
	 * @throws SQLException
	 */
	public static void testRead(GeoPackage geoPackage) throws SQLException {

		TileMatrixSetDao tileMatrixSetDao = geoPackage.getTileMatrixSetDao();

		if (tileMatrixSetDao.isTableExists()) {
			List<TileMatrixSet> results = tileMatrixSetDao.queryForAll();

			for (TileMatrixSet tileMatrixSet : results) {

				// Test the get tile DAO methods
				TileDao dao = geoPackage.getTileDao(tileMatrixSet);
				TestCase.assertNotNull(dao);
				dao = geoPackage.getTileDao(tileMatrixSet.getContents());
				TestCase.assertNotNull(dao);
				dao = geoPackage.getTileDao(tileMatrixSet.getTableName());
				TestCase.assertNotNull(dao);

				TestCase.assertNotNull(dao.getDb());
				TestCase.assertEquals(tileMatrixSet.getId(), dao
						.getTileMatrixSet().getId());
				TestCase.assertEquals(tileMatrixSet.getTableName(),
						dao.getTableName());
				TestCase.assertFalse(dao.getTileMatrices().isEmpty());

				TileTable tileTable = dao.getTable();
				String[] columns = tileTable.getColumnNames();
				int zoomLevelIndex = tileTable.getZoomLevelColumnIndex();
				TestCase.assertTrue(zoomLevelIndex >= 0
						&& zoomLevelIndex < columns.length);
				TestCase.assertEquals(TileTable.COLUMN_ZOOM_LEVEL,
						columns[zoomLevelIndex]);
				int tileColumnIndex = tileTable.getTileColumnColumnIndex();
				TestCase.assertTrue(tileColumnIndex >= 0
						&& tileColumnIndex < columns.length);
				TestCase.assertEquals(TileTable.COLUMN_TILE_COLUMN,
						columns[tileColumnIndex]);
				int tileRowIndex = tileTable.getTileRowColumnIndex();
				TestCase.assertTrue(tileRowIndex >= 0
						&& tileRowIndex < columns.length);
				TestCase.assertEquals(TileTable.COLUMN_TILE_ROW,
						columns[tileRowIndex]);
				int tileDataIndex = tileTable.getTileDataColumnIndex();
				TestCase.assertTrue(tileDataIndex >= 0
						&& tileDataIndex < columns.length);
				TestCase.assertEquals(TileTable.COLUMN_TILE_DATA,
						columns[tileDataIndex]);

				// Query for all
				TileResultSet cursor = dao.queryForAll();
				int count = cursor.getCount();
				int manualCount = 0;
				while (cursor.moveToNext()) {

					TileRow tileRow = cursor.getRow();
					validateTileRow(dao, columns, tileRow);

					manualCount++;
				}
				TestCase.assertEquals(count, manualCount);
				cursor.close();

				// Manually query for all and compare
				Connection connection = dao.getConnection();
				String sql = SQLiteQueryBuilder.buildQueryString(false,
						dao.getTableName(), null, null, null, null, null, null);
				ResultSet resultSet = SQLUtils.query(connection, sql, null);
				int resultSetCount = SQLUtils.count(connection, sql, null);
				cursor = new TileResultSet(tileTable, resultSet, resultSetCount);
				count = cursor.getCount();
				manualCount = 0;
				while (cursor.moveToNext()) {
					manualCount++;
				}
				TestCase.assertEquals(count, manualCount);

				TestCase.assertTrue("No tiles to test", count > 0);

				cursor.close();

				resultSet = SQLUtils.query(connection, sql, null);
				resultSetCount = SQLUtils.count(connection, sql, null);
				cursor = new TileResultSet(tileTable, resultSet, resultSetCount);

				// Choose random tile
				int random = (int) (Math.random() * count);
				cursor.moveToPosition(random);
				TileRow tileRow = cursor.getRow();

				cursor.close();

				// Query by id
				TileRow queryTileRow = dao.queryForIdRow(tileRow.getId());
				TestCase.assertNotNull(queryTileRow);
				TestCase.assertEquals(tileRow.getId(), queryTileRow.getId());

				// Find two non id columns
				TileColumn column1 = null;
				TileColumn column2 = null;
				for (TileColumn column : tileRow.getTable().getColumns()) {
					if (!column.isPrimaryKey()) {
						if (column1 == null) {
							column1 = column;
						} else {
							column2 = column;
							break;
						}
					}
				}

				// Query for equal
				if (column1 != null) {

					Object column1Value = tileRow.getValue(column1.getName());
					Class<?> column1ClassType = column1.getDataType()
							.getClassType();
					boolean column1Decimal = column1ClassType == Double.class
							|| column1ClassType == Float.class;
					ColumnValue column1TileValue;
					if (column1Decimal) {
						column1TileValue = new ColumnValue(column1Value,
								.000001);
					} else {
						column1TileValue = new ColumnValue(column1Value);
					}
					cursor = dao
							.queryForEq(column1.getName(), column1TileValue);
					TestCase.assertTrue(cursor.getCount() > 0);
					boolean found = false;
					while (cursor.moveToNext()) {
						queryTileRow = cursor.getRow();
						TestCase.assertEquals(column1Value,
								queryTileRow.getValue(column1.getName()));
						if (!found) {
							found = tileRow.getId() == queryTileRow.getId();
						}
					}
					TestCase.assertTrue(found);
					cursor.close();

					// Query for field values
					Map<String, ColumnValue> fieldValues = new HashMap<String, ColumnValue>();
					fieldValues.put(column1.getName(), column1TileValue);
					Object column2Value = null;
					ColumnValue column2TileValue;
					if (column2 != null) {
						column2Value = tileRow.getValue(column2.getName());
						Class<?> column2ClassType = column2.getDataType()
								.getClassType();
						boolean column2Decimal = column2ClassType == Double.class
								|| column2ClassType == Float.class;
						if (column2Decimal) {
							column2TileValue = new ColumnValue(column2Value,
									.000001);
						} else {
							column2TileValue = new ColumnValue(column2Value);
						}
						fieldValues.put(column2.getName(), column2TileValue);
					}
					cursor = dao.queryForValueFieldValues(fieldValues);
					TestCase.assertTrue(cursor.getCount() > 0);
					found = false;
					while (cursor.moveToNext()) {
						queryTileRow = cursor.getRow();
						TestCase.assertEquals(column1Value,
								queryTileRow.getValue(column1.getName()));
						if (column2 != null) {
							TestCase.assertEquals(column2Value,
									queryTileRow.getValue(column2.getName()));
						}
						if (!found) {
							found = tileRow.getId() == queryTileRow.getId();
						}
					}
					TestCase.assertTrue(found);
					cursor.close();
				}
			}
		}

	}

	/**
	 * Validate a tile row
	 * 
	 * @param dao
	 * @param columns
	 * @param tileRow
	 */
	private static void validateTileRow(TileDao dao, String[] columns,
			TileRow tileRow) {
		TestCase.assertEquals(columns.length, tileRow.columnCount());

		for (int i = 0; i < tileRow.columnCount(); i++) {
			TileColumn column = tileRow.getTable().getColumns().get(i);
			TestCase.assertEquals(i, column.getIndex());
			TestCase.assertEquals(columns[i], tileRow.getColumnName(i));
			TestCase.assertEquals(i, tileRow.getColumnIndex(columns[i]));
			int rowType = tileRow.getRowColumnType(i);
			Object value = tileRow.getValue(i);

			switch (rowType) {

			case UserCoreResultUtils.FIELD_TYPE_INTEGER:
				TestUtils.validateIntegerValue(value, column.getDataType());
				break;

			case UserCoreResultUtils.FIELD_TYPE_FLOAT:
				TestUtils.validateFloatValue(value, column.getDataType());
				break;

			case UserCoreResultUtils.FIELD_TYPE_STRING:
				TestCase.assertTrue(value instanceof String);
				break;

			case UserCoreResultUtils.FIELD_TYPE_BLOB:
				TestCase.assertTrue(value instanceof byte[]);
				break;

			case UserCoreResultUtils.FIELD_TYPE_NULL:
				TestCase.assertNull(value);
				break;

			}
		}

		TestCase.assertTrue(tileRow.getId() >= 0);
		TestCase.assertTrue(tileRow.getZoomLevel() >= 0);
		TestCase.assertTrue(tileRow.getTileColumn() >= 0);
		TestCase.assertTrue(tileRow.getTileRow() >= 0);
		byte[] tileData = tileRow.getTileData();
		TestCase.assertNotNull(tileData);
		TestCase.assertTrue(tileData.length > 0);

		TileMatrix tileMatrix = dao.getTileMatrix(tileRow.getZoomLevel());
		TestCase.assertNotNull(tileMatrix);

	}

	/**
	 * Test update
	 * 
	 * @param geoPackage
	 * @throws SQLException
	 * @throws IOException
	 */
	public static void testUpdate(GeoPackage geoPackage) throws SQLException,
			IOException {

		TileMatrixSetDao tileMatrixSetDao = geoPackage.getTileMatrixSetDao();

		if (tileMatrixSetDao.isTableExists()) {
			List<TileMatrixSet> results = tileMatrixSetDao.queryForAll();

			for (TileMatrixSet tileMatrixSet : results) {

				TileDao dao = geoPackage.getTileDao(tileMatrixSet);
				TestCase.assertNotNull(dao);

				TileResultSet cursor = dao.queryForAll();
				int count = cursor.getCount();
				if (count > 0) {

					// Choose random tile
					int random = (int) (Math.random() * count);
					cursor.moveToPosition(random);

					String updatedString = null;
					String updatedLimitedString = null;
					Boolean updatedBoolean = null;
					Byte updatedByte = null;
					Short updatedShort = null;
					Integer updatedInteger = null;
					Long updatedLong = null;
					Float updatedFloat = null;
					Double updatedDouble = null;
					byte[] updatedBytes = null;
					byte[] updatedLimitedBytes = null;

					TileRow originalRow = cursor.getRow();
					TileRow tileRow = cursor.getRow();

					try {
						tileRow.setValue(tileRow.getPkColumnIndex(), 9);
						TestCase.fail("Updated the primary key value");
					} catch (GeoPackageException e) {
						// expected
					}

					for (TileColumn tileColumn : dao.getTable().getColumns()) {
						if (!tileColumn.isPrimaryKey()) {

							switch (tileRow.getRowColumnType(tileColumn
									.getIndex())) {

							case UserCoreResultUtils.FIELD_TYPE_STRING:
								if (updatedString == null) {
									updatedString = UUID.randomUUID()
											.toString();
								}
								if (tileColumn.getMax() != null) {
									if (updatedLimitedString != null) {
										if (updatedString.length() > tileColumn
												.getMax()) {
											updatedLimitedString = updatedString
													.substring(0, tileColumn
															.getMax()
															.intValue());
										} else {
											updatedLimitedString = updatedString;
										}
									}
									tileRow.setValue(tileColumn.getIndex(),
											updatedLimitedString);
								} else {
									tileRow.setValue(tileColumn.getIndex(),
											updatedString);
								}
								break;
							case UserCoreResultUtils.FIELD_TYPE_INTEGER:
								switch (tileColumn.getDataType()) {
								case BOOLEAN:
									if (updatedBoolean == null) {
										updatedBoolean = !((Boolean) tileRow
												.getValue(tileColumn.getIndex()));
									}
									tileRow.setValue(tileColumn.getIndex(),
											updatedBoolean);
									break;
								case TINYINT:
									if (updatedByte == null) {
										updatedByte = (byte) (((int) (Math
												.random() * (Byte.MAX_VALUE + 1))) * (Math
												.random() < .5 ? 1 : -1));
									}
									tileRow.setValue(tileColumn.getIndex(),
											updatedByte);
									break;
								case SMALLINT:
									if (updatedShort == null) {
										updatedShort = (short) (((int) (Math
												.random() * (Short.MAX_VALUE + 1))) * (Math
												.random() < .5 ? 1 : -1));
									}
									tileRow.setValue(tileColumn.getIndex(),
											updatedShort);
									break;
								case MEDIUMINT:
									if (updatedInteger == null) {
										updatedInteger = (int) (((int) (Math
												.random() * (Integer.MAX_VALUE + 1))) * (Math
												.random() < .5 ? 1 : -1));
									}
									tileRow.setValue(tileColumn.getIndex(),
											updatedInteger);
									break;
								case INT:
								case INTEGER:
									if (updatedLong == null) {
										updatedLong = (long) (((int) (Math
												.random() * (Long.MAX_VALUE + 1))) * (Math
												.random() < .5 ? 1 : -1));
									}
									tileRow.setValue(tileColumn.getIndex(),
											updatedLong);
									break;
								default:
									TestCase.fail("Unexpected integer type: "
											+ tileColumn.getDataType());
								}
								break;
							case UserCoreResultUtils.FIELD_TYPE_FLOAT:
								switch (tileColumn.getDataType()) {
								case FLOAT:
									if (updatedFloat == null) {
										updatedFloat = (float) Math.random()
												* Float.MAX_VALUE;
									}
									tileRow.setValue(tileColumn.getIndex(),
											updatedFloat);
									break;
								case DOUBLE:
								case REAL:
									if (updatedDouble == null) {
										updatedDouble = Math.random()
												* Double.MAX_VALUE;
									}
									tileRow.setValue(tileColumn.getIndex(),
											updatedDouble);
									break;
								default:
									TestCase.fail("Unexpected float type: "
											+ tileColumn.getDataType());
								}
								break;
							case UserCoreResultUtils.FIELD_TYPE_BLOB:
								if (updatedBytes == null) {
									updatedBytes = TestUtils.getTileBytes();
								}
								if (tileColumn.getMax() != null) {
									if (updatedLimitedBytes != null) {
										if (updatedBytes.length > tileColumn
												.getMax()) {
											updatedLimitedBytes = new byte[tileColumn
													.getMax().intValue()];
											ByteBuffer.wrap(
													updatedBytes,
													0,
													tileColumn.getMax()
															.intValue()).get(
													updatedLimitedBytes);
										} else {
											updatedLimitedBytes = updatedBytes;
										}
									}
									tileRow.setValue(tileColumn.getIndex(),
											updatedLimitedBytes);
								} else {
									tileRow.setValue(tileColumn.getIndex(),
											updatedBytes);
								}
								break;
							default:
							}

						}
					}

					cursor.close();
					TestCase.assertEquals(1, dao.update(tileRow));

					long id = tileRow.getId();
					TileRow readRow = dao.queryForIdRow(id);
					TestCase.assertNotNull(readRow);
					TestCase.assertEquals(originalRow.getId(), readRow.getId());

					for (String readColumnName : readRow.getColumnNames()) {

						TileColumn readTileColumn = readRow
								.getColumn(readColumnName);
						if (!readTileColumn.isPrimaryKey()) {
							switch (readRow.getRowColumnType(readColumnName)) {
							case UserCoreResultUtils.FIELD_TYPE_STRING:
								if (readTileColumn.getMax() != null) {
									TestCase.assertEquals(updatedLimitedString,
											readRow.getValue(readTileColumn
													.getIndex()));
								} else {
									TestCase.assertEquals(updatedString,
											readRow.getValue(readTileColumn
													.getIndex()));
								}
								break;
							case UserCoreResultUtils.FIELD_TYPE_INTEGER:
								switch (readTileColumn.getDataType()) {
								case BOOLEAN:
									TestCase.assertEquals(updatedBoolean,
											readRow.getValue(readTileColumn
													.getIndex()));
									break;
								case TINYINT:
									TestCase.assertEquals(updatedByte,
											readRow.getValue(readTileColumn
													.getIndex()));
									break;
								case SMALLINT:
									TestCase.assertEquals(updatedShort,
											readRow.getValue(readTileColumn
													.getIndex()));
									break;
								case MEDIUMINT:
									TestCase.assertEquals(updatedInteger,
											readRow.getValue(readTileColumn
													.getIndex()));
									break;
								case INT:
								case INTEGER:
									TestCase.assertEquals(updatedLong,
											readRow.getValue(readTileColumn
													.getIndex()));
									break;
								default:
									TestCase.fail("Unexpected integer type: "
											+ readTileColumn.getDataType());
								}
								break;
							case UserCoreResultUtils.FIELD_TYPE_FLOAT:
								switch (readTileColumn.getDataType()) {
								case FLOAT:
									TestCase.assertEquals(updatedFloat,
											readRow.getValue(readTileColumn
													.getIndex()));
									break;
								case DOUBLE:
								case REAL:
									TestCase.assertEquals(updatedDouble,
											readRow.getValue(readTileColumn
													.getIndex()));
									break;
								default:
									TestCase.fail("Unexpected integer type: "
											+ readTileColumn.getDataType());
								}
								break;
							case UserCoreResultUtils.FIELD_TYPE_BLOB:
								if (readTileColumn.getMax() != null) {
									GeoPackageGeometryDataUtils
											.compareByteArrays(
													updatedLimitedBytes,
													(byte[]) readRow
															.getValue(readTileColumn
																	.getIndex()));
								} else {
									byte[] readBytes = (byte[]) readRow
											.getValue(readTileColumn.getIndex());
									GeoPackageGeometryDataUtils
											.compareByteArrays(updatedBytes,
													readBytes);
								}
								break;
							default:
							}
						}

					}

				}
				cursor.close();
			}
		}

	}

	/**
	 * Test create
	 * 
	 * @param geoPackage
	 * @throws SQLException
	 */
	public static void testCreate(GeoPackage geoPackage) throws SQLException {

		TileMatrixSetDao tileMatrixSetDao = geoPackage.getTileMatrixSetDao();

		if (tileMatrixSetDao.isTableExists()) {
			List<TileMatrixSet> results = tileMatrixSetDao.queryForAll();

			for (TileMatrixSet tileMatrixSet : results) {

				TileDao dao = geoPackage.getTileDao(tileMatrixSet);
				TestCase.assertNotNull(dao);

				TileResultSet cursor = dao.queryForAll();
				int count = cursor.getCount();
				if (count > 0) {

					// Choose random tile
					int random = (int) (Math.random() * count);
					cursor.moveToPosition(random);

					TileRow tileRow = cursor.getRow();
					cursor.close();

					// Find the largest zoom level
					TileMatrixDao tileMatrixDao = geoPackage.getTileMatrixDao();
					QueryBuilder<TileMatrix, TileMatrixKey> qb = tileMatrixDao
							.queryBuilder();
					qb.where().eq(TileMatrix.COLUMN_TABLE_NAME,
							tileMatrixSet.getTableName());
					qb.orderBy(TileMatrix.COLUMN_ZOOM_LEVEL, false);
					PreparedQuery<TileMatrix> query = qb.prepare();
					TileMatrix tileMatrix = tileMatrixDao.queryForFirst(query);
					long highestZoomLevel = tileMatrix.getZoomLevel();

					// Create new row from existing
					long id = tileRow.getId();
					tileRow.resetId();
					tileRow.setZoomLevel(highestZoomLevel + 1);
					long newRowId = dao.create(tileRow);
					TestCase.assertEquals(newRowId, tileRow.getId());

					// Verify original still exists and new was created
					tileRow = dao.queryForIdRow(id);
					TestCase.assertNotNull(tileRow);
					TileRow queryTileRow = dao.queryForIdRow(newRowId);
					TestCase.assertNotNull(queryTileRow);
					cursor = dao.queryForAll();
					TestCase.assertEquals(count + 1, cursor.getCount());
					cursor.close();

					// Create new row with copied values from another
					TileRow newRow = dao.newRow();
					for (TileColumn column : dao.getTable().getColumns()) {

						if (column.isPrimaryKey()) {
							try {
								newRow.setValue(column.getName(), 10);
								TestCase.fail("Set primary key on new row");
							} catch (GeoPackageException e) {
								// Expected
							}
						} else {
							newRow.setValue(column.getName(),
									tileRow.getValue(column.getName()));
						}
					}

					newRow.setZoomLevel(queryTileRow.getZoomLevel() + 1);
					long newRowId2 = dao.create(newRow);

					TestCase.assertEquals(newRowId2, newRow.getId());

					// Verify new was created
					TileRow queryTileRow2 = dao.queryForIdRow(newRowId2);
					TestCase.assertNotNull(queryTileRow2);
					cursor = dao.queryForAll();
					TestCase.assertEquals(count + 2, cursor.getCount());
					cursor.close();
				}
				cursor.close();
			}
		}

	}

	/**
	 * Test delete
	 * 
	 * @param geoPackage
	 * @throws SQLException
	 */
	public static void testDelete(GeoPackage geoPackage) throws SQLException {

		TileMatrixSetDao tileMatrixSetDao = geoPackage.getTileMatrixSetDao();

		if (tileMatrixSetDao.isTableExists()) {
			List<TileMatrixSet> results = tileMatrixSetDao.queryForAll();

			for (TileMatrixSet tileMatrixSet : results) {

				TileDao dao = geoPackage.getTileDao(tileMatrixSet);
				TestCase.assertNotNull(dao);

				TileResultSet cursor = dao.queryForAll();
				int count = cursor.getCount();
				if (count > 0) {

					// Choose random tile
					int random = (int) (Math.random() * count);
					cursor.moveToPosition(random);

					TileRow tileRow = cursor.getRow();
					cursor.close();

					// Delete row
					TestCase.assertEquals(1, dao.delete(tileRow));

					// Verify deleted
					TileRow queryTileRow = dao.queryForIdRow(tileRow.getId());
					TestCase.assertNull(queryTileRow);
					cursor = dao.queryForAll();
					TestCase.assertEquals(count - 1, cursor.getCount());
					cursor.close();
				}
				cursor.close();
			}

		}
	}

	/**
	 * Test getZoomLevel
	 * 
	 * @param geoPackage
	 * @throws SQLException
	 */
	public static void testGetZoomLevel(GeoPackage geoPackage)
			throws SQLException {

		TileMatrixSetDao tileMatrixSetDao = geoPackage.getTileMatrixSetDao();

		if (tileMatrixSetDao.isTableExists()) {
			List<TileMatrixSet> results = tileMatrixSetDao.queryForAll();

			for (TileMatrixSet tileMatrixSet : results) {

				TileDao dao = geoPackage.getTileDao(tileMatrixSet);

				List<TileMatrix> tileMatrices = dao.getTileMatrices();

				for (TileMatrix tileMatrix : tileMatrices) {

					double width = tileMatrix.getPixelXSize()
							* tileMatrix.getTileWidth();
					double height = tileMatrix.getPixelYSize()
							* tileMatrix.getTileHeight();

					long zoomLevel = dao.getZoomLevel(width);
					TestCase.assertEquals(tileMatrix.getZoomLevel(), zoomLevel);

					zoomLevel = dao.getZoomLevel(height);
					TestCase.assertEquals(tileMatrix.getZoomLevel(), zoomLevel);

					zoomLevel = dao.getZoomLevel(width + 1);
					TestCase.assertEquals(tileMatrix.getZoomLevel(), zoomLevel);

					zoomLevel = dao.getZoomLevel(height + 1);
					TestCase.assertEquals(tileMatrix.getZoomLevel(), zoomLevel);

					zoomLevel = dao.getZoomLevel(width - 1);
					TestCase.assertEquals(tileMatrix.getZoomLevel(), zoomLevel);

					zoomLevel = dao.getZoomLevel(height - 1);
					TestCase.assertEquals(tileMatrix.getZoomLevel(), zoomLevel);

				}

			}

		}

	}

	/**
	 * Test queryByRange
	 * 
	 * @param geoPackage
	 * @throws SQLException
	 */
	public static void testQueryByRange(GeoPackage geoPackage)
			throws SQLException {

		TileMatrixSetDao tileMatrixSetDao = geoPackage.getTileMatrixSetDao();

		if (tileMatrixSetDao.isTableExists()) {
			List<TileMatrixSet> results = tileMatrixSetDao.queryForAll();

			for (TileMatrixSet tileMatrixSet : results) {

				TileDao dao = geoPackage.getTileDao(tileMatrixSet);

				List<TileMatrix> tileMatrices = dao.getTileMatrices();

				for (TileMatrix tileMatrix : tileMatrices) {

					double width = tileMatrix.getPixelXSize()
							* tileMatrix.getTileWidth();
					double height = tileMatrix.getPixelYSize()
							* tileMatrix.getTileHeight();

					long zoomLevel = dao.getZoomLevel(width);

					BoundingBox setProjectionBoundingBox = tileMatrixSet
							.getBoundingBox();
					BoundingBox setWebMercatorBoundingBox = ProjectionFactory
							.getProjection(tileMatrixSet.getSrs())
							.getTransformation(
									ProjectionConstants.EPSG_WEB_MERCATOR)
							.transform(setProjectionBoundingBox);
					BoundingBox boundingBox = new BoundingBox(-180.0, 180.0,
							-90.0, 90.0);
					BoundingBox webMercatorBoundingBox = ProjectionFactory
							.getProjection(
									ProjectionConstants.EPSG_WORLD_GEODETIC_SYSTEM)
							.getTransformation(
									ProjectionConstants.EPSG_WEB_MERCATOR)
							.transform(boundingBox);

					TileGrid tileGrid = TileBoundingBoxUtils.getTileGrid(
							setWebMercatorBoundingBox,
							tileMatrix.getMatrixWidth(),
							tileMatrix.getMatrixHeight(),
							webMercatorBoundingBox);

					TileResultSet cursor = dao.queryByTileGrid(tileGrid,
							zoomLevel);
					int cursorCount = cursor != null ? cursor.getCount() : 0;
					TileResultSet expectedCursor = dao.queryForTile(zoomLevel);

					TestCase.assertEquals(expectedCursor.getCount(),
							cursorCount);
					if (cursor != null) {
						cursor.close();
					}
					expectedCursor.close();

					double maxLon = (360.0 * Math.random()) - 180.0;
					double minLon = ((maxLon + 180.0) * Math.random()) - 180.0;
					double maxLat = (180.0 * Math.random()) - 90.0;
					double minLat = ((maxLon + 90.0) * Math.random()) - 90.0;
					boundingBox = new BoundingBox(minLon, maxLon, minLat,
							maxLat);
					webMercatorBoundingBox = ProjectionFactory
							.getProjection(
									ProjectionConstants.EPSG_WORLD_GEODETIC_SYSTEM)
							.getTransformation(
									ProjectionConstants.EPSG_WEB_MERCATOR)
							.transform(boundingBox);
					tileGrid = TileBoundingBoxUtils.getTileGrid(
							setWebMercatorBoundingBox,
							tileMatrix.getMatrixWidth(),
							tileMatrix.getMatrixHeight(),
							webMercatorBoundingBox);
					cursor = dao.queryByTileGrid(tileGrid, zoomLevel);
					cursorCount = cursor != null ? cursor.getCount() : 0;

					if (tileGrid != null) {
						int count = 0;
						for (long column = tileGrid.getMinX(); column <= tileGrid
								.getMaxX(); column++) {
							for (long row = tileGrid.getMinY(); row <= tileGrid
									.getMaxY(); row++) {
								TileRow tileRow = dao.queryForTile(column, row,
										zoomLevel);
								if (tileRow != null) {
									count++;
								}
							}
						}
						TestCase.assertEquals(count, cursorCount);
					} else {
						TestCase.assertEquals(0, cursorCount);
					}
					if (cursor != null) {
						cursor.close();
					}

				}

			}

		}

	}

	/**
	 * Test querying for the bounding box at a tile matrix zoom level
	 * 
	 * @param geoPackage
	 * @throws SQLException
	 */
	public static void testTileMatrixBoundingBox(GeoPackage geoPackage)
			throws SQLException {

		TileMatrixSetDao tileMatrixSetDao = geoPackage.getTileMatrixSetDao();

		if (tileMatrixSetDao.isTableExists()) {
			List<TileMatrixSet> results = tileMatrixSetDao.queryForAll();

			for (TileMatrixSet tileMatrixSet : results) {

				TileDao dao = geoPackage.getTileDao(tileMatrixSet);
				TestCase.assertNotNull(dao);

				BoundingBox totalBoundingBox = tileMatrixSet.getBoundingBox();
				TestCase.assertEquals(totalBoundingBox, dao.getBoundingBox());

				List<TileMatrix> tileMatrices = dao.getTileMatrices();

				for (TileMatrix tileMatrix : tileMatrices) {

					long zoomLevel = tileMatrix.getZoomLevel();
					int count = dao.count(zoomLevel);
					TileGrid totalTileGrid = dao.getTileGrid(zoomLevel);
					TileGrid tileGrid = dao.queryForTileGrid(zoomLevel);
					BoundingBox boundingBox = dao.getBoundingBox(zoomLevel);

					if (totalTileGrid.equals(tileGrid)) {
						TestCase.assertEquals(totalBoundingBox, boundingBox);
					} else {
						TestCase.assertTrue(totalBoundingBox.getMinLongitude() <= boundingBox
								.getMinLongitude());
						TestCase.assertTrue(totalBoundingBox.getMaxLongitude() >= boundingBox
								.getMaxLongitude());
						TestCase.assertTrue(totalBoundingBox.getMinLatitude() <= boundingBox
								.getMinLatitude());
						TestCase.assertTrue(totalBoundingBox.getMaxLatitude() >= boundingBox
								.getMaxLatitude());
					}

					int deleted = 0;
					if (tileMatrix.getMatrixHeight() > 1
							|| tileMatrix.getMatrixWidth() > 1) {

						for (int column = 0; column < tileMatrix
								.getMatrixWidth(); column++) {
							TestCase.assertEquals(1,
									dao.deleteTile(column, 0, zoomLevel));
							TestCase.assertEquals(1, dao
									.deleteTile(column,
											tileMatrix.getMatrixHeight() - 1,
											zoomLevel));
							deleted += 2;
						}

						for (int row = 1; row < tileMatrix.getMatrixHeight() - 1; row++) {
							TestCase.assertEquals(1,
									dao.deleteTile(0, row, zoomLevel));
							TestCase.assertEquals(1, dao.deleteTile(
									tileMatrix.getMatrixWidth() - 1, row,
									zoomLevel));
							deleted += 2;
						}
					} else {
						TestCase.assertEquals(1,
								dao.deleteTile(0, 0, zoomLevel));
						deleted++;
					}

					int updatedCount = dao.count(zoomLevel);
					TestCase.assertEquals(count - deleted, updatedCount);

					TileGrid updatedTileGrid = dao.queryForTileGrid(zoomLevel);
					BoundingBox updatedBoundingBox = dao
							.getBoundingBox(zoomLevel);

					if (tileMatrix.getMatrixHeight() <= 2
							&& tileMatrix.getMatrixWidth() <= 2) {
						TestCase.assertNull(updatedTileGrid);
						TestCase.assertNull(updatedBoundingBox);
					} else {
						TestCase.assertNotNull(updatedTileGrid);
						TestCase.assertNotNull(updatedBoundingBox);

						TestCase.assertEquals(tileGrid.getMinX() + 1,
								updatedTileGrid.getMinX());
						TestCase.assertEquals(tileGrid.getMaxX() - 1,
								updatedTileGrid.getMaxX());
						TestCase.assertEquals(tileGrid.getMinY() + 1,
								updatedTileGrid.getMinY());
						TestCase.assertEquals(tileGrid.getMaxY() - 1,
								updatedTileGrid.getMaxY());

						BoundingBox tileGridBoundingBox = TileBoundingBoxUtils
								.getBoundingBox(totalBoundingBox, tileMatrix,
										updatedTileGrid);
						TestCase.assertEquals(tileGridBoundingBox,
								updatedBoundingBox);
					}
				}

			}
		}

	}

}
