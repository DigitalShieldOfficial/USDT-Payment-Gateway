<?php
namespace DSPay\Api;

final class JsonNumber
{
    public $raw;
    public $plain;
    public $integer;
    public function __construct($raw)
    {
        $this->raw = $raw;
        $this->integer = strpbrk($raw, '.eE') === false;
        if (!preg_match('/^(-?)([0-9]+)(?:\.([0-9]+))?(?:[eE]([+-]?[0-9]+))?$/D', $raw, $m)) throw new \InvalidArgumentException('Invalid JSON number');
        $exponent = isset($m[4]) ? (float)$m[4] : 0;
        $digits = $m[2] . (isset($m[3]) ? $m[3] : '');
        $point = strlen($m[2]) + $exponent;
        if (abs($exponent) > 4096 || strlen($digits) > 4096 || $point > 4096) throw new \InvalidArgumentException('JSON number exceeds 4096-digit limit');
        $point = (int)$point;
        $plain = $point <= 0 ? '0.' . str_repeat('0', -$point) . $digits
            : ($point >= strlen($digits) ? $digits . str_repeat('0', $point - strlen($digits)) : substr($digits, 0, $point) . '.' . substr($digits, $point));
        $plain = preg_replace('/^0+(?=[0-9])/', '', $plain);
        $this->plain = ($m[1] !== '' && (!$this->integer || preg_match('/[1-9]/', $plain)) ? '-' : '') . $plain;
    }
    public function canonical()
    {
        if (!preg_match('/[1-9]/', $this->plain)) return '0';
        return strpos($this->plain, '.') !== false ? rtrim(rtrim($this->plain, '0'), '.') : $this->plain;
    }
}

final class LosslessJson
{
    private $raw;
    private $i = 0;
    public static function parse($raw)
    {
        $p = new self(); $p->raw = $raw;
        $value = $p->value(); $p->space();
        if ($p->i !== strlen($raw)) throw new \InvalidArgumentException('Trailing JSON data');
        return $value;
    }
    private function space()
    {
        while ($this->i < strlen($this->raw) && strpos(" \t\r\n", $this->raw[$this->i]) !== false) $this->i++;
    }
    private function value()
    {
        $this->space();
        if ($this->i >= strlen($this->raw)) throw new \InvalidArgumentException('JSON value required');
        $c = $this->raw[$this->i];
        if ($c === '"') {
            $start = $this->i++;
            while ($this->i < strlen($this->raw)) {
                $c = $this->raw[$this->i++];
                if ($c === '"') {
                    $v = json_decode(substr($this->raw, $start, $this->i - $start));
                    if (json_last_error() !== JSON_ERROR_NONE) throw new \InvalidArgumentException('Invalid JSON string');
                    return $v;
                }
                if ($c === '\\') $this->i++;
            }
            throw new \InvalidArgumentException('Unterminated JSON string');
        }
        if ($c === '{' || $c === '[') {
            $object = $c === '{'; $end = $object ? '}' : ']'; $this->i++;
            $out = $object ? new \stdClass() : array();
            $this->space();
            if (isset($this->raw[$this->i]) && $this->raw[$this->i] === $end) { $this->i++; return $out; }
            while (true) {
                if ($object) {
                    $this->space();
                    if (!isset($this->raw[$this->i]) || $this->raw[$this->i] !== '"') throw new \InvalidArgumentException('JSON key required');
                    $key = $this->value(); $this->space();
                    if (!isset($this->raw[$this->i]) || $this->raw[$this->i++] !== ':') throw new \InvalidArgumentException('JSON colon required');
                    if (property_exists($out, $key)) throw new \InvalidArgumentException('Duplicate JSON field: ' . $key);
                    $out->$key = $this->value();
                } else $out[] = $this->value();
                $this->space();
                if (!isset($this->raw[$this->i])) throw new \InvalidArgumentException('JSON separator required');
                $c = $this->raw[$this->i++];
                if ($c === $end) return $out;
                if ($c !== ',') throw new \InvalidArgumentException('JSON separator required');
            }
        }
        foreach (array('true' => true, 'false' => false, 'null' => null) as $token => $v) {
            if (substr($this->raw, $this->i, strlen($token)) === $token) { $this->i += strlen($token); return $v; }
        }
        if (!preg_match('/^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?/', substr($this->raw, $this->i), $m)) throw new \InvalidArgumentException('Invalid JSON value');
        $this->i += strlen($m[0]); return new JsonNumber($m[0]);
    }
    public static function encode($v)
    {
        if ($v instanceof JsonNumber) return $v->canonical();
        if (is_object($v)) {
            $fields = get_object_vars($v);
            // Python and Java sort nested JSON object keys by UTF-16 code units.
            uksort($fields, function ($a, $b) { return strcmp(self::utf16($a), self::utf16($b)); });
            $parts = array();
            foreach ($fields as $key => $item) $parts[] = self::stringValue((string)$key) . ':' . self::encode($item);
            return '{' . implode(',', $parts) . '}';
        }
        if (is_array($v)) {
            if ($v === array() || array_keys($v) === range(0, count($v) - 1)) return '[' . implode(',', array_map(array(__CLASS__, 'encode'), $v)) . ']';
            return self::encode((object)$v);
        }
        if (is_float($v)) {
            if (!is_finite($v)) throw new \InvalidArgumentException("JSON numbers must be finite");
            return (new JsonNumber(json_encode($v)))->canonical();
        }
        if (is_string($v)) return self::stringValue($v);
        return json_encode($v, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    }
    private static function stringValue($v)
    {
        $encoded = json_encode($v, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
        if ($encoded === false) throw new \InvalidArgumentException('JSON strings must be valid UTF-8');
        return str_replace(array('\\u2028', '\\u2029'), array("\xe2\x80\xa8", "\xe2\x80\xa9"), $encoded);
    }
    private static function utf16($text)
    {
        // No mbstring/iconv dependency: JSON escaping exposes UTF-16 code units.
        $quoted = json_encode((string)$text);
        $out = '';
        for ($i = 1; $i < strlen($quoted) - 1; $i++) {
            if ($quoted[$i] === '\\' && isset($quoted[$i + 1]) && $quoted[$i + 1] === 'u') {
                $out .= pack('n', hexdec(substr($quoted, $i + 2, 4))); $i += 5;
            } else {
                if ($quoted[$i] === '\\') { $i++; $ch = json_decode('"\\' . $quoted[$i] . '"'); }
                else $ch = $quoted[$i];
                $out .= pack('n', ord($ch));
            }
        }
        return $out;
    }
}
