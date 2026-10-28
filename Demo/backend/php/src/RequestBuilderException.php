<?php

namespace DSPay\Api;

final class RequestBuilderException extends \Exception
{
    /**
     * @var string
     */
    private $method;

    /**
     * @var array
     */
    private $errors;

    /**
     * @param string $message
     * @param int $responseCode
     * @param string $uri
     * @param array $errors
     * @param \Throwable|null $previous
     */
    public function __construct($message, $responseCode = 0, $uri = '', array $errors = array(), $previous = null)
    {
        $this->method = $uri;
        $this->errors = $errors;

        parent::__construct($message, $responseCode, $previous);
    }

    /**
     * @return string
     */
    public function getMethod()
    {
        return $this->method;
    }

    /**
     * @return array
     */
    public function getErrors()
    {
        return $this->errors;
    }
}