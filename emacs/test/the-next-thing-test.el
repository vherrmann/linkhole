;;; the-next-thing-test.el --- Tests for the-next-thing  -*- lexical-binding: t; -*-

(require 'ert)
(require 'the-next-thing)

(ert-deftest tnt-extract-plain ()
  (should (equal (the-next-thing--extract-urls "see https://a.example/x and http://b.example")
                 '("https://a.example/x" "http://b.example"))))

(ert-deftest tnt-extract-org-links ()
  (should (equal (the-next-thing--extract-urls
                  "* TODO [[https://youtu.be/abc][Cool video]]\n- [[https://c.example/p?q=1&r=2]]")
                 '("https://youtu.be/abc" "https://c.example/p?q=1&r=2"))))

(ert-deftest tnt-extract-trailing-punctuation ()
  (should (equal (the-next-thing--extract-urls "Look (https://a.example/x), then https://b.example/y.")
                 '("https://a.example/x" "https://b.example/y")))
  (should (equal (the-next-thing--extract-urls "https://en.wikipedia.org/wiki/Foo_(bar)")
                 '("https://en.wikipedia.org/wiki/Foo_(bar)"))))

(ert-deftest tnt-extract-dedupes-and-ignores-non-http ()
  (should (equal (the-next-thing--extract-urls "ftp://x/ https://a/ https://a/ mailto:x@y")
                 '("https://a/")))
  (should-not (the-next-thing--extract-urls "no links here")))

(ert-deftest tnt-url-at-point-org-link ()
  (with-temp-buffer
    (org-mode)
    (insert "text [[https://a.example/p][desc]] more")
    (goto-char (point-min))
    (search-forward "desc")
    (should (equal (the-next-thing--url-at-point) "https://a.example/p"))))

(ert-deftest tnt-url-at-point-plain ()
  (with-temp-buffer
    (insert "visit https://a.example/q now")
    (goto-char (point-min))
    (search-forward "a.example")
    (should (equal (the-next-thing--url-at-point) "https://a.example/q"))))

(ert-deftest tnt-entry ()
  (should (equal (the-next-thing--entry
                  '((id . 7) (url . "https://a/") (title . nil) (state . "unread")
                    (created_at . 0)))
                 (list 7 (vector "unread" (the-next-thing--format-time 0) "—" "https://a/")))))

(ert-deftest tnt-token-fallback ()
  (let ((the-next-thing-server-url "http://nowhere.invalid:1")
        (the-next-thing-token "abc")
        (auth-sources nil))
    (should (equal (the-next-thing--token) "abc"))))

(provide 'the-next-thing-test)
