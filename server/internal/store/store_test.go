package store

import (
	"errors"
	"path/filepath"
	"testing"
	"time"
)

func open(t *testing.T) *Store {
	t.Helper()
	s, err := Open(filepath.Join(t.TempDir(), "t.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	return s
}

func TestAddDedupe(t *testing.T) {
	s := open(t)
	a, created, err := s.Add("https://a.example/", nil)
	if err != nil || !created {
		t.Fatalf("first add: created=%v err=%v", created, err)
	}
	b, created, err := s.Add("https://a.example/", ptr("note"))
	if err != nil || created || b.ID != a.ID {
		t.Fatalf("dup add: %+v created=%v err=%v", b, created, err)
	}
}

func TestLatestStableUntilMarked(t *testing.T) {
	s := open(t)
	fixed := time.UnixMilli(1000)
	s.SetClock(func() time.Time { return fixed }) // same ms: ordering must still hold
	a, _, _ := s.Add("https://a/", nil)
	b, _, _ := s.Add("https://b/", nil)
	for i := 0; i < 3; i++ {
		l, err := s.Latest()
		if err != nil || l.ID != b.ID {
			t.Fatalf("latest = %+v, want %d (err %v)", l, b.ID, err)
		}
	}
	if _, err := s.SetState(b.ID, Read); err != nil {
		t.Fatal(err)
	}
	if l, _ := s.Latest(); l.ID != a.ID {
		t.Fatalf("after read latest = %d, want %d", l.ID, a.ID)
	}
	if _, err := s.SetState(b.ID, Unread); err != nil {
		t.Fatal(err)
	}
	if l, _ := s.Latest(); l.ID != b.ID {
		t.Fatalf("after reset latest = %d, want %d", l.ID, b.ID)
	}
}

func TestEmptyLatestRandom(t *testing.T) {
	s := open(t)
	if _, err := s.Latest(); !errors.Is(err, ErrNotFound) {
		t.Fatalf("latest: %v", err)
	}
	if _, err := s.Random(); !errors.Is(err, ErrNotFound) {
		t.Fatalf("random: %v", err)
	}
}

func TestRandomExcludesLatest(t *testing.T) {
	s := open(t)
	a, _, _ := s.Add("https://a/", nil)
	if r, _ := s.Random(); r.ID != a.ID {
		t.Fatal("single item: random should fall back to latest")
	}
	s.Add("https://b/", nil)
	c, _, _ := s.Add("https://c/", nil)
	seen := map[int64]bool{}
	for i := 0; i < 100; i++ {
		r, err := s.Random()
		if err != nil || r.ID == c.ID {
			t.Fatalf("random returned latest or error: %+v %v", r, err)
		}
		seen[r.ID] = true
	}
	if len(seen) != 2 {
		t.Fatalf("expected both non-latest items, saw %v", seen)
	}
}

func TestListFilters(t *testing.T) {
	s := open(t)
	clock := int64(1000)
	s.SetClock(func() time.Time { return time.UnixMilli(clock) })
	a, _, _ := s.Add("https://a/", nil)
	clock = 2000
	b, _, _ := s.Add("https://b/", nil)
	clock = 3000
	s.SetState(a.ID, Uninteresting)

	if l, _ := s.List("unread", 0); len(l) != 1 || l[0].ID != b.ID {
		t.Fatalf("unread: %+v", l)
	}
	if l, _ := s.List("all", 0); len(l) != 2 || l[0].ID != b.ID {
		t.Fatalf("all (newest first): %+v", l)
	}
	if l, _ := s.List("all", 2500); len(l) != 1 || l[0].ID != a.ID {
		t.Fatalf("updated_since: %+v", l)
	}
	if _, err := s.List("bogus", 0); !errors.Is(err, ErrInvalidState) {
		t.Fatalf("bogus state: %v", err)
	}
}

func TestSetStateDeleteErrors(t *testing.T) {
	s := open(t)
	a, _, _ := s.Add("https://a/", nil)
	if _, err := s.SetState(a.ID, "bogus"); !errors.Is(err, ErrInvalidState) {
		t.Fatal(err)
	}
	if _, err := s.SetState(999, Read); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	if err := s.Delete(a.ID); err != nil {
		t.Fatal(err)
	}
	if err := s.Delete(a.ID); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
}

func TestTitles(t *testing.T) {
	s := open(t)
	a, _, _ := s.Add("https://a/", nil)
	b, _, _ := s.Add("https://b/", nil)
	if p, _ := s.PendingTitles(); len(p) != 2 {
		t.Fatalf("pending: %d", len(p))
	}
	s.SetTitle(a.ID, "Title A")
	s.SetTitle(b.ID, "") // failed fetch still counts as fetched
	if p, _ := s.PendingTitles(); len(p) != 0 {
		t.Fatalf("pending after: %d", len(p))
	}
	got, _ := s.Get(a.ID)
	if got.Title == nil || *got.Title != "Title A" || !got.TitleFetched {
		t.Fatalf("%+v", got)
	}
	got, _ = s.Get(b.ID)
	if got.Title != nil || !got.TitleFetched {
		t.Fatalf("%+v", got)
	}
}

func ptr(s string) *string { return &s }
