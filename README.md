# aws-sig4

## [!] Forked

Forked from [https://github.com/sharetribe/aws-sig4](https://github.com/sharetribe/aws-sig4)

- clj-http v2 -> v3
- lein -> tools deps

[AWS signature v4 request signing](http://docs.aws.amazon.com/general/latest/gr/signature-version-4.html)
implemented as an clj-http middleware. This is a pure clojure
implementation and does not require the AWS SDK. It only provides the
middleware and doesn't do anything else like autodetecting AWS
credentials. Providing correct credentials, region and service name is
the responsibility of caller.

**This library has seen only limited production use so far. Bugs and problems may exist. Please file issues if you encounter any problems.**

## Installation

TODO.

## Usage

Require clj-http client and the middleware.

```clojure
(require '[aws-sig4.middleware :as aws-sig4]
         '[clj-http.client :as http])
```

Build an auth middleware instance by passing in AWS parameters.

```clojure
(def wrap-aws-auth (aws-sig4/build-wrap-aws-auth {:region "us-east-1"
                                                  :service "es"
                                                  :access-key "AWS_ACCESS_KEY"
                                                  :secret-key "AWS_SECRET_KEY"
                                                  :token "AWS_SESSION_TOKEN" ; optional
                                                  }))

```

Wrap outgoing requests with the middleware instance. Optionally
include wrap-aws-date to ensure that all requests have either the
required Date header, or if not generate X-Amz-Date
header. wrap-aws-date must be defined first in the middleware chain.

```clojure
(http/with-additional-middleware
  [wrap-aws-auth aws-sig4/wrap-aws-date]
  (http/get "https://my-es-instance.us-east-1.es.amazonaws.com/_cat/indices"))
```

That's it.

Hint: Consider using
[temporary security credentials](http://docs.aws.amazon.com/IAM/latest/UserGuide/id_credentials_temp_use-resources.html#RequestWithSTS)
when accessing AWS services.

## License

Author of original code: Sharetribe Ltd. [https://github.com/sharetribe/aws-sig4](https://github.com/sharetribe/aws-sig4)

Distributed under [The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0)
