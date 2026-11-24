;;; linkhole-test.el --- Tests for linkhole  -*- lexical-binding: t; -*-

(require 'ert)
(require 'linkhole)

(ert-deftest tnt-extract-plain ()
  (should (equal (linkhole--extract-urls "see https://a.example/x and http://b.example")
                 '("https://a.example/x" "http://b.example"))))

(ert-deftest tnt-extract-org-links ()
  (should (equal (linkhole--extract-urls
                  "* TODO [[https://youtu.be/abc][Cool video]]\n- [[https://c.example/p?q=1&r=2]]")
                 '("https://youtu.be/abc" "https://c.example/p?q=1&r=2"))))

(ert-deftest tnt-extract-trailing-punctuation ()
  (should (equal (linkhole--extract-urls "Look (https://a.example/x), then https://b.example/y.")
                 '("https://a.example/x" "https://b.example/y")))
  (should (equal (linkhole--extract-urls "https://en.wikipedia.org/wiki/Foo_(bar)")
                 '("https://en.wikipedia.org/wiki/Foo_(bar)"))))

(ert-deftest tnt-extract-dedupes-and-ignores-non-http ()
  (should (equal (linkhole--extract-urls "ftp://x/ https://a/ https://a/ mailto:x@y")
                 '("https://a/")))
  (should-not (linkhole--extract-urls "no links here")))

(ert-deftest tnt-url-at-point-org-link ()
  (with-temp-buffer
    (org-mode)
    (insert "text [[https://a.example/p][desc]] more")
    (goto-char (point-min))
    (search-forward "desc")
    (should (equal (linkhole--url-at-point) "https://a.example/p"))))

(ert-deftest tnt-url-at-point-plain ()
  (with-temp-buffer
    (insert "visit https://a.example/q now")
    (goto-char (point-min))
    (search-forward "a.example")
    (should (equal (linkhole--url-at-point) "https://a.example/q"))))

(ert-deftest tnt-entry ()
  (should (equal (linkhole--entry
                  '((id . 7) (url . "https://a/") (title . nil) (state . "unread")
                    (created_at . 0)))
                 (list 7 (vector "unread" (linkhole--format-time 0) "—" "https://a/")))))

(ert-deftest tnt-token-fallback ()
  (let ((linkhole-server-url "http://nowhere.invalid:1")
        (linkhole-token "abc")
        (auth-sources nil))
    (should (equal (linkhole--token) "abc"))))

(provide 'linkhole-test)
