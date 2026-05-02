using System.ComponentModel.DataAnnotations;

namespace Dotnet10;

/// <summary>
/// Endpoint filter that validates a request DTO using DataAnnotations.
/// Equivalent to @Valid on a JAX-RS controller method parameter in the Quarkus project:
/// invalid input is rejected with HTTP 400 and a problem-details body before the handler runs.
/// </summary>
public sealed class ValidationFilter<T> : IEndpointFilter where T : notnull
{
    public async ValueTask<object?> InvokeAsync(EndpointFilterInvocationContext context, EndpointFilterDelegate next)
    {
        var argument = context.Arguments.OfType<T>().FirstOrDefault();
        if (argument is null)
        {
            return await next(context);
        }

        var validationContext = new ValidationContext(argument);
        var results = new List<ValidationResult>();
        if (!Validator.TryValidateObject(argument, validationContext, results, validateAllProperties: true))
        {
            var errors = results
                .SelectMany(r => r.MemberNames.DefaultIfEmpty(string.Empty), (r, m) => (Member: m, r.ErrorMessage))
                .GroupBy(x => x.Member)
                .ToDictionary(
                    g => g.Key,
                    g => g.Select(x => x.ErrorMessage ?? string.Empty).ToArray());

            return Results.ValidationProblem(errors);
        }

        return await next(context);
    }
}
