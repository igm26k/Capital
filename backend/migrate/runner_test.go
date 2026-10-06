package migrate

import (
	"testing"
	"testing/fstest"
)

func TestMigrationHistoryRejectsGapsAndDuplicateVersions(t *testing.T) {
	for _, files := range []fstest.MapFS{
		{"0002_ledger.sql": {Data: []byte("SELECT 1;")}},
		{"0001_identity.sql": {Data: []byte("SELECT 1;")}, "0001_other.sql": {Data: []byte("SELECT 2;")}},
		{"0000_identity.sql": {Data: []byte("SELECT 1;")}},
		{"unsafe.sql": {Data: []byte("SELECT 1;")}},
	} {
		if _, err := Load(files); err == nil {
			t.Fatal("unsafe migration order accepted")
		}
	}
}

func TestSQLContentChangeInvalidatesChecksum(t *testing.T) {
	original, err := Load(fstest.MapFS{"0001_identity.sql": {Data: []byte("CREATE TABLE example(id integer);")}})
	if err != nil {
		t.Fatal(err)
	}
	changed, err := Load(fstest.MapFS{"0001_identity.sql": {Data: []byte("CREATE TABLE example(id bigint);")}})
	if err != nil {
		t.Fatal(err)
	}
	if original[0].Checksum == changed[0].Checksum {
		t.Fatal("schema change not detected")
	}
}

func TestEmptyDirectoryDoesNotInventApplicationMigrations(t *testing.T) {
	migrations, err := Load(fstest.MapFS{"README.md": {Data: []byte("pending S3-02A")}})
	if err != nil || len(migrations) != 0 {
		t.Fatal("invented migration", err)
	}
}
