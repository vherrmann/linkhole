// Package store persists items in SQLite.
package store

import (
	"database/sql"
	"errors"
	"fmt"
	"time"

	_ "modernc.org/sqlite"
)

const (
	Unread        = "unread"
	Read          = "read"
	Uninteresting = "uninteresting"
)

var (
	ErrNotFound     = errors.New("not found")
	ErrInvalidState = errors.New("invalid state")
)

// Item is a queued link. Timestamps are unix milliseconds.
type Item struct {
	ID           int64   `json:"id"`
	URL          string  `json:"url"`
	Title        *string `json:"title"`
	Note         *string `json:"note"`
	State        string  `json:"state"`
	CreatedAt    int64   `json:"created_at"`
	UpdatedAt    int64   `json:"updated_at"`
	TitleFetched bool    `json:"title_fetched"`
}

type Store struct {
	db  *sql.DB
	now func() time.Time
}

const schema = `
CREATE TABLE IF NOT EXISTS items (
	id INTEGER PRIMARY KEY AUTOINCREMENT,
	url TEXT NOT NULL UNIQUE,
	title TEXT,
	note TEXT,
	state TEXT NOT NULL DEFAULT 'unread' CHECK (state IN ('unread','read','uninteresting')),
	created_at INTEGER NOT NULL,
	updated_at INTEGER NOT NULL,
	title_fetched INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS items_state_created ON items(state, created_at);
CREATE INDEX IF NOT EXISTS items_updated ON items(updated_at);
`

// Open opens (and migrates) the database at path.
func Open(path string) (*Store, error) {
	db, err := sql.Open("sqlite", path)
	if err != nil {
		return nil, err
	}
	// A single connection serialises access; plenty for one user, and it
	// keeps in-memory databases coherent.
	db.SetMaxOpenConns(1)
	for _, p := range []string{"PRAGMA busy_timeout = 5000", "PRAGMA journal_mode = WAL", "PRAGMA foreign_keys = ON"} {
		if _, err := db.Exec(p); err != nil {
			db.Close()
			return nil, fmt.Errorf("%s: %w", p, err)
		}
	}
	if _, err := db.Exec(schema); err != nil {
		db.Close()
		return nil, err
	}
	return &Store{db: db, now: time.Now}, nil
}

func (s *Store) Close() error { return s.db.Close() }

// SetClock overrides the time source (tests).
func (s *Store) SetClock(now func() time.Time) { s.now = now }

func (s *Store) nowMs() int64 { return s.now().UnixMilli() }

const cols = `id, url, title, note, state, created_at, updated_at, title_fetched`

type scanner interface{ Scan(...any) error }

func scan(r scanner) (Item, error) {
	var it Item
	var title, note sql.NullString
	var fetched int
	if err := r.Scan(&it.ID, &it.URL, &title, &note, &it.State, &it.CreatedAt, &it.UpdatedAt, &fetched); err != nil {
		return Item{}, err
	}
	if title.Valid {
		it.Title = &title.String
	}
	if note.Valid {
		it.Note = &note.String
	}
	it.TitleFetched = fetched != 0
	return it, nil
}

func (s *Store) get(id int64) (Item, error) {
	it, err := scan(s.db.QueryRow(`SELECT `+cols+` FROM items WHERE id = ?`, id))
	if errors.Is(err, sql.ErrNoRows) {
		return Item{}, ErrNotFound
	}
	return it, err
}

func (s *Store) Get(id int64) (Item, error) { return s.get(id) }

// Add inserts url. If it already exists the existing item is returned with
// created == false.
func (s *Store) Add(url string, note *string) (it Item, created bool, err error) {
	if note != nil && *note == "" {
		note = nil
	}
	ts := s.nowMs()
	// Keep created_at strictly increasing so latest is deterministic.
	var maxCreated sql.NullInt64
	if err := s.db.QueryRow(`SELECT MAX(created_at) FROM items`).Scan(&maxCreated); err != nil {
		return Item{}, false, err
	}
	if maxCreated.Valid && ts <= maxCreated.Int64 {
		ts = maxCreated.Int64 + 1
	}
	res, err := s.db.Exec(
		`INSERT INTO items (url, note, state, created_at, updated_at) VALUES (?, ?, 'unread', ?, ?)
		 ON CONFLICT(url) DO NOTHING`, url, note, ts, ts)
	if err != nil {
		return Item{}, false, err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return Item{}, false, err
	}
	if n == 1 {
		id, err := res.LastInsertId()
		if err != nil {
			return Item{}, false, err
		}
		it, err = s.get(id)
		return it, true, err
	}
	it, err = scan(s.db.QueryRow(`SELECT `+cols+` FROM items WHERE url = ?`, url))
	return it, false, err
}

// List returns items newest first. state "all" (or "") matches every state.
// since > 0 restricts to items with updated_at >= since.
func (s *Store) List(state string, since int64) ([]Item, error) {
	q := `SELECT ` + cols + ` FROM items WHERE 1=1`
	var args []any
	if state != "" && state != "all" {
		if !validState(state) {
			return nil, ErrInvalidState
		}
		q += ` AND state = ?`
		args = append(args, state)
	}
	if since > 0 {
		q += ` AND updated_at >= ?`
		args = append(args, since)
	}
	q += ` ORDER BY created_at DESC, id DESC`
	rows, err := s.db.Query(q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	items := []Item{}
	for rows.Next() {
		it, err := scan(rows)
		if err != nil {
			return nil, err
		}
		items = append(items, it)
	}
	return items, rows.Err()
}

// Latest returns the newest unread item, or ErrNotFound.
func (s *Store) Latest() (Item, error) {
	it, err := scan(s.db.QueryRow(`SELECT ` + cols + ` FROM items WHERE state = 'unread'
		ORDER BY created_at DESC, id DESC LIMIT 1`))
	if errors.Is(err, sql.ErrNoRows) {
		return Item{}, ErrNotFound
	}
	return it, err
}

// Random returns a random unread item other than the latest one, falling back
// to the latest when it is the only unread item. ErrNotFound if none unread.
func (s *Store) Random() (Item, error) {
	latest, err := s.Latest()
	if err != nil {
		return Item{}, err
	}
	it, err := scan(s.db.QueryRow(`SELECT `+cols+` FROM items WHERE state = 'unread' AND id != ?
		ORDER BY RANDOM() LIMIT 1`, latest.ID))
	if errors.Is(err, sql.ErrNoRows) {
		return latest, nil
	}
	return it, err
}

func validState(s string) bool { return s == Unread || s == Read || s == Uninteresting }

func (s *Store) SetState(id int64, state string) (Item, error) {
	if !validState(state) {
		return Item{}, ErrInvalidState
	}
	res, err := s.db.Exec(`UPDATE items SET state = ?, updated_at = ? WHERE id = ?`, state, s.nowMs(), id)
	if err != nil {
		return Item{}, err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return Item{}, ErrNotFound
	}
	return s.get(id)
}

func (s *Store) Delete(id int64) error {
	res, err := s.db.Exec(`DELETE FROM items WHERE id = ?`, id)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// SetTitle records the outcome of a title fetch. An empty title means the
// fetch failed or found nothing; the item is still marked as fetched.
func (s *Store) SetTitle(id int64, title string) error {
	var t any
	if title != "" {
		t = title
	}
	_, err := s.db.Exec(`UPDATE items SET title = ?, title_fetched = 1, updated_at = ? WHERE id = ?`,
		t, s.nowMs(), id)
	return err
}

// PendingTitles returns items whose title has not been fetched yet.
func (s *Store) PendingTitles() ([]Item, error) {
	rows, err := s.db.Query(`SELECT ` + cols + ` FROM items WHERE title_fetched = 0 ORDER BY id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var items []Item
	for rows.Next() {
		it, err := scan(rows)
		if err != nil {
			return nil, err
		}
		items = append(items, it)
	}
	return items, rows.Err()
}
